#!/usr/bin/env bash
set -euo pipefail

REGION=ap-northeast-2
NAMESPACE=Edupilot/App

: "${EDUPILOT_ENVIRONMENT:?Set EDUPILOT_ENVIRONMENT to dev or prod}"
: "${SNS_TOPIC_ARN:?Set SNS_TOPIC_ARN to the existing CloudWatch alarm topic ARN}"

case "$EDUPILOT_ENVIRONMENT" in
  dev|prod) ;;
  *) echo "EDUPILOT_ENVIRONMENT must be dev or prod" >&2; exit 1 ;;
esac
case "$SNS_TOPIC_ARN" in
  arn:aws:sns:ap-northeast-2:*:*) ;;
  *) echo "SNS_TOPIC_ARN must name the existing ap-northeast-2 topic" >&2; exit 1 ;;
esac

main_group="/edupilot/${EDUPILOT_ENVIRONMENT}/main"
ai_group="/edupilot/${EDUPILOT_ENVIRONMENT}/ai"

# These shapes come from TurnResponseValidator/HttpAiClient, AccessLogFilter,
# and ai-service api/turn.py. A completed stream is SUCCESS, not FAILED.
invalid_pattern='{ $.environment = "'"$EDUPILOT_ENVIRONMENT"'" && $.errorCode = "AI_RESPONSE_INVALID" }'
http_5xx_pattern='{ $.environment = "'"$EDUPILOT_ENVIRONMENT"'" && $.message = "HTTP request completed" && $.status >= 500 }'
turn_failed_pattern='{ $.environment = "'"$EDUPILOT_ENVIRONMENT"'" && $.status = "FAILED" && ($.message = "turn stream failed" || $.message = "turn stream failed unexpectedly" || $.message = "turn failed" || $.message = "turn failed unexpectedly") }'

main_invalid_sample='{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"AI response validation failed","errorCode":"AI_RESPONSE_INVALID"}'
main_5xx_sample='{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"HTTP request completed","status":503}'
ai_failed_sample='{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"turn stream failed","status":"FAILED"}'

assert_filter_matches() {
  local pattern=$1 sample=$2 expected=$3 actual
  actual=$(aws --region "$REGION" logs test-metric-filter \
    --filter-pattern "$pattern" \
    --log-event-messages "$sample" \
    --query 'length(matches)' --output text)
  if [[ "$actual" != "$expected" ]]; then
    echo "Metric filter test failed: expected $expected match(es), got $actual: $pattern" >&2
    exit 1
  fi
}

# Validate positive and negative log shapes before changing any AWS resources.
assert_filter_matches "$invalid_pattern" "$main_invalid_sample" 1
assert_filter_matches "$invalid_pattern" "$main_5xx_sample" 0
assert_filter_matches "$invalid_pattern" '{"environment":"other","errorCode":"AI_RESPONSE_INVALID"}' 0
assert_filter_matches "$http_5xx_pattern" "$main_5xx_sample" 1
assert_filter_matches "$http_5xx_pattern" "$main_invalid_sample" 0
assert_filter_matches "$turn_failed_pattern" "$ai_failed_sample" 1
for message in 'turn stream failed unexpectedly' 'turn failed' 'turn failed unexpectedly'; do
  sample='{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"'"$message"'","status":"FAILED"}'
  assert_filter_matches "$turn_failed_pattern" "$sample" 1
done
assert_filter_matches "$turn_failed_pattern" '{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"turn stream completed","status":"SUCCESS"}' 0
assert_filter_matches "$turn_failed_pattern" '{"environment":"'"$EDUPILOT_ENVIRONMENT"'","message":"quiz generation failed","status":"FAILED"}' 0

for group in "$main_group" "$ai_group"; do
  aws --region "$REGION" logs put-retention-policy \
    --log-group-name "$group" --retention-in-days 14
done

put_filter_and_alarm() {
  local group=$1 metric=$2 pattern=$3 transformation
  transformation='[{"metricName":"'"$metric"'","metricNamespace":"'"$NAMESPACE"'","metricValue":"1","unit":"Count","dimensions":{"Environment":"$.environment"}}]'

  aws --region "$REGION" logs put-metric-filter \
    --log-group-name "$group" \
    --filter-name "$metric" \
    --filter-pattern "$pattern" \
    --metric-transformations "$transformation"

  aws --region "$REGION" cloudwatch put-metric-alarm \
    --alarm-name "Edupilot-${EDUPILOT_ENVIRONMENT}-${metric}" \
    --alarm-description "${metric}: 3 or more matching events within 5 minutes (${EDUPILOT_ENVIRONMENT})" \
    --namespace "$NAMESPACE" \
    --metric-name "$metric" \
    --dimensions "Name=Environment,Value=${EDUPILOT_ENVIRONMENT}" \
    --statistic Sum --unit Count --period 300 \
    --evaluation-periods 1 --datapoints-to-alarm 1 \
    --threshold 3 --comparison-operator GreaterThanOrEqualToThreshold \
    --treat-missing-data notBreaching \
    --alarm-actions "$SNS_TOPIC_ARN" --ok-actions "$SNS_TOPIC_ARN"
}

put_filter_and_alarm "$main_group" AiResponseInvalidCount "$invalid_pattern"
put_filter_and_alarm "$main_group" Http5xxCount "$http_5xx_pattern"
put_filter_and_alarm "$ai_group" AiTurnFailedCount "$turn_failed_pattern"

echo "Configured ${EDUPILOT_ENVIRONMENT} application log retention, metric filters, and alarms."
