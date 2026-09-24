# 애플리케이션 로그 수집·에러율 경보 (#409)

이 문서는 **운영자 적용 절차**다. 리포 변경만으로 AWS나 서버에는 적용되지 않는다. 먼저 dev에서 검증하고, 같은 절차를 prod 저부하 시간에 반복한다. 기존 EC2 인프라 경보 5종과 SNS 토픽은 변경하지 않는다.

## 현재 로그 계약과 필터

| 지표 (`Edupilot/App`) | 로그 그룹 | 필터 패턴 | 코드 근거 |
| --- | --- | --- | --- |
| `AiResponseInvalidCount` | `/edupilot/{env}/main` | `{ $.environment = "{env}" && $.errorCode = "AI_RESPONSE_INVALID" }` | `TurnResponseValidator`와 `HttpAiClient`의 구조화 오류 필드 |
| `Http5xxCount` | `/edupilot/{env}/main` | `{ $.environment = "{env}" && $.message = "HTTP request completed" && $.status >= 500 }` | `AccessLogFilter`의 완료 로그와 숫자 `status` |
| `AiTurnFailedCount` | `/edupilot/{env}/ai` | `{ $.environment = "{env}" && $.status = "FAILED" && ($.message = "turn stream failed" \|\| $.message = "turn stream failed unexpectedly" \|\| $.message = "turn failed" \|\| $.message = "turn failed unexpectedly") }` | `ai-service`의 스트리밍·비스트리밍 턴 실패 종료 로그 |

AI의 `turn stream completed`는 `status=SUCCESS`다. 이 문자열을 FAILED 필터에 넣으면 실제 장애가 누락된다. 로그 예시는 민감정보가 없는 **실제 코드 출력 형태**를 줄인 것이다.

```json
{"level":"WARN","service":"main-service","environment":"dev","message":"AI response validation failed","errorCode":"AI_RESPONSE_INVALID"}
{"level":"INFO","service":"main-service","environment":"dev","message":"HTTP request completed","status":503}
{"level":"WARNING","service":"ai-service","environment":"dev","message":"turn stream failed","status":"FAILED"}
```

각 필터는 매칭 1건당 `1`을 게시한다. `{env}`는 스크립트 실행 시 `dev` 또는 `prod`로 대체된다. `Environment` 차원은 필터에도 등장하는 로그의 `environment` 필드에서 뽑고, 경보 이름은 `Edupilot-{env}-{metric}`이다. **5분 Sum ≥ 3, 1개 평가 구간, 누락 데이터 정상**이며 ALARM과 OK 모두 기존 SNS 토픽으로 보낸다. 새 필터는 과거 로그를 소급 집계하지 않는다. 하루 약 30턴 규모에서 5분에 3건은 초깃값이며, 실제 정상·오탐 빈도에 따라 조정한다. `AI_RESPONSE_INVALID` 로그는 구조화 `errorCode`가 남는 경로만 집계한다. API의 모든 거부가 이 로그를 보장하는 것은 아니다. `Http5xxCount`도 기존 `AccessLogFilter`가 제외하는 `/api/health` 요청은 집계하지 않는다.

## 준비·권한

1. AWS CLI v2가 있는 관리자 단말에서 계정·리전을 확인하고, 기존 경보의 `AlarmActions`에서 **기존 SNS 토픽 ARN**을 확인한다. 새 SNS 토픽은 만들지 않는다.

   ```bash
   aws sts get-caller-identity --query Account --output text
   aws cloudwatch describe-alarms --region ap-northeast-2 \
     --query 'MetricAlarms[].[AlarmName,AlarmActions]' --output table
   export SNS_TOPIC_ARN='arn:aws:sns:ap-northeast-2:<ACCOUNT_ID>:<EXISTING_TOPIC>'
   aws sns get-topic-attributes --region ap-northeast-2 --topic-arn "$SNS_TOPIC_ARN"
   ```

2. `infra/iam/ec2-cloudwatch-logs-policy.json`의 `<ACCOUNT_ID>`를 실제 AWS 계정 ID로 치환해, 기존 EC2 인스턴스 역할 `Ai-Tutor-EC2-SSM-Role`에 `EdupilotCloudWatchLogs` 인라인 정책으로 부착한다. `CreateLogGroup`·`PutRetentionPolicy`는 `/edupilot/*` 로그 그룹에, `CreateLogStream`·`PutLogEvents`는 그 하위 스트림에만 허용한다. Docker 데몬은 인스턴스 역할을 쓰므로 AWS 키를 `.env`나 compose에 넣지 않는다. 역할 정책 전파를 확인한 후 컨테이너를 재생성한다.

   ```bash
   account_id=$(aws sts get-caller-identity --query Account --output text)
   sed "s/<ACCOUNT_ID>/$account_id/g" infra/iam/ec2-cloudwatch-logs-policy.json \
     > /tmp/edupilot-cloudwatch-logs-policy.json
   aws iam put-role-policy --role-name Ai-Tutor-EC2-SSM-Role \
     --policy-name EdupilotCloudWatchLogs \
     --policy-document file:///tmp/edupilot-cloudwatch-logs-policy.json
   ```

3. `infra/cloudwatch/app-alarms.sh`는 **관리자 단말 자격증명**으로 실행한다. 이 단말에는 `logs:TestMetricFilter`, `logs:PutRetentionPolicy`, `logs:PutMetricFilter`, `cloudwatch:PutMetricAlarm`이 필요하다. 위 EC2 역할 정책에는 필터·경보 변경 권한을 넣지 않았다. 이후 조회와 dev 연동 테스트에는 Logs/CloudWatch 조회 권한 및 `logs:CreateLogStream`, `logs:PutLogEvents`가 별도로 필요하다.

## dev 적용 → 검증 → prod 적용

1. **dev 서버** `.env`에 `EDUPILOT_ENVIRONMENT=dev`, **prod 서버**에는 `EDUPILOT_ENVIRONMENT=prod`를 설정한다. 기존 배포 워크플로의 `ENVIRONMENT` 값이 있으면 그것이 우선하므로 서로 다르게 지정하지 않는다. `SPRING_PROFILES_ACTIVE`, AI의 `ENVIRONMENT`, awslogs 그룹이 같은 값인지 아래 `config` 출력으로 확인한다. dev 로그가 `environment:"prod"`로 찍히던 경우 이 설정으로 수정된다. 현재 CI 워크플로는 dev/prod에 `ENVIRONMENT`를 각각 명시한다.

   ```bash
   docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml \
     config | grep -E 'SPRING_PROFILES_ACTIVE:|ENVIRONMENT:|awslogs-group:'
   docker version --format '{{.Server.Version}}'
   docker compose version
   ```

2. 서버 Docker Engine의 [dual logging](https://docs.docker.com/engine/logging/dual-logging/) 지원을 확인한다. `awslogs` 원격 드라이버를 쓰더라도 로컬 읽기 캐시는 기본 활성화(기본 최대 20 MB × 5개/컨테이너)이고 compose에는 `cache-disabled: "false"`를 명시했다. **Docker 20.10+를 운영 기준**으로 삼되 버전 숫자만 믿지 말고 아래 명령으로 실제 동작을 확인한다. `docker-compose.prod.yml`만 오버레이 적용한다. 베이스 compose의 mysql/nginx 로그 드라이버는 바꾸지 않는다.

   ```bash
   docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml \
     up -d main-service ai-service
   docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml \
     logs --since 5m main-service
   docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml \
     logs --since 5m ai-service
   ```

   `restart`는 로깅 드라이버/환경변수 변경을 반영하지 않는다. `up -d`로 재생성해야 한다. 정적 스트림 이름(`main-service`, `ai-service`)은 각 환경에서 서비스당 컨테이너 1개인 현재 구성을 전제로 한다. 복제 운영 시에는 컨테이너별 고유 스트림 이름으로 바꿔야 한다. `compose logs`가 실패하거나 비어 있는데 요청 로그가 실제 발생한다면 **여기서 중단**한다. 직전 정상 compose 오버레이로 되돌려 `up -d`로 재생성하고, `json-file` 로컬 진단이 복구됐는지 확인한다. 그다음 [CloudWatch Agent 파일 수집 설정](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Agent-Configuration-File-Details.html)으로 Docker `json-file` 로그를 tail하는 방식을 적용한다. Agent가 보내는 이벤트가 `{"log":"{...}"}`처럼 Docker 래퍼 JSON일 수 있으므로, 위 필터를 그대로 적용하지 말고 CloudWatch에 도착한 한 줄이 **최상위 앱 JSON**인지 확인한 뒤 파싱/필터 패턴을 별도로 검증해야 한다. 이 대체 방식이 검증되기 전에는 경보를 운영 완료로 표시하지 않는다. 원격 awslogs 쓰기가 실패하면 로컬 캐시 기록도 실패할 수 있다는 Docker 제한도 있으므로, AWS 통신 장애 중에는 Docker 데몬 로그를 함께 점검한다.

3. CloudWatch Logs에서 `/edupilot/dev/main`, `/edupilot/dev/ai`의 스트림 생성과 JSON 한 줄의 `environment=dev`를 확인한다. 관리자 단말에서 토픽 ARN을 설정한 뒤 스크립트를 실행한다. 스크립트는 AWS `TestMetricFilter`로 양성/음성 샘플을 먼저 검사하고, 그룹 14일 보존·필터·경보를 `put-*`으로 설정한다. 같은 값으로 재실행해도 중복 필터/경보를 만들지 않는다. 그룹이 생성되기 전 실행하면 실패하므로 순서를 지킨다. 기존 그룹에 14일 초과 로그가 있었다면 보존 정책 적용 후 만료되므로 먼저 확인한다.

   ```bash
   export EDUPILOT_ENVIRONMENT=dev
   export SNS_TOPIC_ARN='arn:aws:sns:ap-northeast-2:<ACCOUNT_ID>:<EXISTING_TOPIC>'
   bash infra/cloudwatch/app-alarms.sh
   aws logs describe-log-groups --region ap-northeast-2 \
     --log-group-name-prefix /edupilot/dev/ \
     --query 'logGroups[].[logGroupName,retentionInDays]' --output table
   aws cloudwatch describe-alarms --region ap-northeast-2 \
     --alarm-name-prefix Edupilot-dev- \
     --query 'MetricAlarms[].[AlarmName,StateValue,TreatMissingData]' --output table
   ```

4. **dev에서만** 안전한 end-to-end 테스트를 한다. 잘못된 AI 내부 토큰 호출은 AI 인증 거부일 뿐 `AI_RESPONSE_INVALID`나 턴 실패 종료 로그를 보장하지 않으므로 이 경보의 적절한 유발 방법이 아니다. 대신 Python 3가 있는 관리자 단말에서 dev 그룹의 별도 스트림에 민감정보 없는 모의 앱 JSON 3건을 5분 안에 넣는다. 아래 예시는 `AiResponseInvalidCount` 체인만 테스트한다. 나머지 두 필터는 스크립트의 `TestMetricFilter`로 이미 형태 검증하며, 필요 시 각각의 dev 그룹에 위 표의 샘플을 같은 방식으로 넣어 별도 경보까지 확인한다. **모의 로그는 애플리케이션이 실제로 그 이벤트를 방출하는지까지 증명하지 않는다.** 실제 로그 한 줄도 따로 확인한다.

   ```bash
   group=/edupilot/dev/main
   stream="app-alarms-smoke-$(date +%s)"
   aws logs create-log-stream --region ap-northeast-2 \
     --log-group-name "$group" --log-stream-name "$stream"
   events=$(python3 -c 'import json,time; now=int(time.time()*1000); line=json.dumps({"environment":"dev","message":"AI response validation failed","errorCode":"AI_RESPONSE_INVALID"},separators=(",",":")); print(json.dumps([{"timestamp":now+i,"message":line} for i in range(3)]))')
   aws logs put-log-events --region ap-northeast-2 \
     --log-group-name "$group" --log-stream-name "$stream" --log-events "$events"
   ```

   10분 안에 `Edupilot-dev-AiResponseInvalidCount`가 ALARM으로 바뀌고 SNS 메일이 오는지 확인한다. 이후 새 이벤트가 없는 다음 평가 구간에 OK 전환 메일도 확인한다. 메일이 없으면 SNS 구독 확인 → metric의 `Environment=dev` 차원 확인 → 필터 매치 및 로그 그룹/리전 확인 순으로 조사한다. dev 성공 후 같은 절차로 prod를 적용하되 **prod에 모의 오류 로그는 넣지 않는다**.

5. 적용 전후 `docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml ps -q main-service ai-service`로 컨테이너 ID를 얻어 `docker stats --no-stream <main-ID> <ai-ID>`로 메모리·CPU를 기록한다. 두 컨테이너의 메모리 증가 목표는 각각 50 MB 미만이다. 적용 24시간 뒤 PDFBox의 `Could not get Adobe-Korea1-3 UC2 map` WARN과 Tomcat `invalid cookie` INFO가 사라졌는지, main·ai 로그 수집량과 경보 오탐 건수를 기록한다.

## Logs Insights 조회 예시

대상 그룹을 먼저 선택한다. 아래는 앱 JSON의 최상위 필드가 파싱된 경우다.

```sql
fields @timestamp, service, environment, traceId, level, message
| filter traceId = '찾을-traceId'
| sort @timestamp desc
| limit 100
```

```sql
fields errorCode
| filter ispresent(errorCode)
| stats count(*) as events by errorCode
| sort events desc
```

```sql
fields @timestamp, traceId, eventType, durationMs, message
| filter ispresent(durationMs) and (message = 'turn stream completed' or message = 'turn stream failed' or message = 'turn stream failed unexpectedly' or message = 'turn completed' or message = 'turn failed' or message = 'turn failed unexpectedly')
| sort durationMs desc
| limit 20
```

## 비용·점검 기록

월 **1~2 GB는 아직 실측이 아닌 목표치**다. 서울 리전 단가 가정(수집 `$0.76/GB`, 저장 `$0.03/GB-month`)으로 무료 구간 밖 1~2 GB 수집분은 `$0.76~$1.52`다. [CloudWatch 가격표](https://aws.amazon.com/cloudwatch/pricing/)의 Logs 무료 5 GB가 이 계정의 기존 사용량을 포함해 남아 있다면 이 추가 로그의 수집/저장 요금은 `$0`일 수 있다. 그러나 **전체 경보 구성 비용 `$0`을 보장하지 않는다**. 3개 custom metric과 3개 standard alarm을 환경별로 만들며, 기존 5개 인프라 경보와 dev까지 합치면 계정 단위 무료 10개 alarm metric 한도를 넘을 수 있다. SNS 및 Insights 사용량도 별도 확인한다. 단가와 무료 한도는 계정·리전·시점에 따라 AWS Pricing Calculator에서 최종 확인한다.

월별 AWS Cost Explorer/Billing의 CloudWatch Logs `DataProcessing-Bytes`·`TimedStorage-ByteHrs` 및 CloudWatch metric/alarm 비용을 확인한다. 24시간 실측 바이트를 30일로 환산하고 14일 보존 정책을 점검한다. 경보가 너무 민감하면 `5분 ≥ 3`의 운영 데이터와 하루 총 턴 수를 비교해 임계값을 조정하고, 변경 이유·시각·전후 알림 건수를 여기에 기록한다.

| 환경/적용일 | dual logging (`compose logs`) | Insights traceId | 3건 유발→ALARM/OK·SNS | 메모리/CPU 전후 | 24h 수집량·비용 | 담당자 |
| --- | --- | --- | --- | --- | --- | --- |
| dev / 미실시 | 미검증 | 미검증 | 미검증 | 미측정 | 미측정 | - |
| prod / 미실시 | 미검증 | 미검증 | dev 후 적용 | 미측정 | 미측정 | - |

## 출처

- [Docker awslogs 옵션](https://docs.docker.com/engine/logging/drivers/awslogs/) 및 [dual logging 제한·기본 캐시](https://docs.docker.com/engine/logging/dual-logging/)
- [CloudWatch JSON 필터 문법](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html), [metric filter 차원·비용](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntaxForMetricFilters.html)
- [CloudWatch 가격·무료 한도](https://aws.amazon.com/cloudwatch/pricing/)
