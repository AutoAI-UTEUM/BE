#!/usr/bin/env bash
set -euo pipefail
umask 077

COMPOSE_DIR="${EDUPILOT_BACKUP_COMPOSE_DIR:-/opt/edupilot}"
BACKUP_DIR="${EDUPILOT_BACKUP_DIR:-/opt/edupilot/backup}"
LOG_FILE="${EDUPILOT_BACKUP_LOG_FILE:-/var/log/edupilot-backup.log}"
BUCKET="${EDUPILOT_DB_BACKUP_BUCKET:-uteum-db-backup}"
REGION="${AWS_REGION:-ap-northeast-2}"
SNS_TOPIC_ARN="${EDUPILOT_DB_BACKUP_SNS_TOPIC_ARN:-}"
FILE_NAME="edupilot-prod-$(TZ=Asia/Seoul date +%Y%m%d-%H%M).sql.gz"
FINAL_FILE="$BACKUP_DIR/$FILE_NAME"
TEMP_FILE=""

cleanup() {
  if [[ -n "$TEMP_FILE" ]]; then
    rm -f -- "$TEMP_FILE"
  fi
}
trap cleanup EXIT

log_line() {
  printf '%s\n' "$1" >> "$LOG_FILE"
}

fail() {
  local stage="$1"
  local message="FAIL stage=$stage file=$FILE_NAME"
  if ! log_line "$message"; then
    printf '%s\n' "$message" >&2
  fi
  if [[ -n "$SNS_TOPIC_ARN" ]] && command -v aws >/dev/null 2>&1; then
    if ! aws sns publish --region "$REGION" --topic-arn "$SNS_TOPIC_ARN" \
      --subject 'EduPilot prod DB backup failed' --message "$message" >/dev/null 2>&1; then
      if ! log_line "FAIL stage=sns-publish file=$FILE_NAME"; then
        printf 'FAIL stage=sns-publish file=%s\n' "$FILE_NAME" >&2
      fi
    fi
  else
    if ! log_line "FAIL stage=sns-unavailable file=$FILE_NAME"; then
      printf 'FAIL stage=sns-unavailable file=%s\n' "$FILE_NAME" >&2
    fi
  fi
  exit 1
}

[[ -n "$SNS_TOPIC_ARN" ]] || fail "sns-topic-not-configured"
[[ -d "$COMPOSE_DIR" ]] || fail "compose-directory"
mkdir -p -- "$BACKUP_DIR" || fail "backup-directory"
chmod 700 "$BACKUP_DIR" || fail "backup-directory-permissions"
: >> "$LOG_FILE" || fail "log-write"
[[ ! -e "$FINAL_FILE" ]] || fail "filename-collision"
cd "$COMPOSE_DIR" || fail "compose-directory"
TEMP_FILE="$(mktemp "$BACKUP_DIR/.db-backup-XXXXXXXX")" || fail "temp-file"

set +e
docker compose --env-file .env exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines --triggers edupilot' \
  2>/dev/null | gzip -c > "$TEMP_FILE" 2>/dev/null
pipeline_status=("${PIPESTATUS[@]}")
set -e
(( pipeline_status[0] == 0 )) || fail "mysqldump"
(( pipeline_status[1] == 0 )) || fail "gzip"

SIZE_BYTES="$(wc -c < "$TEMP_FILE")" || fail "size-check"
(( SIZE_BYTES > 1048576 )) || fail "size-check"
gzip -t "$TEMP_FILE" || fail "gzip-integrity"
mv -- "$TEMP_FILE" "$FINAL_FILE" || fail "local-finalize"
TEMP_FILE=""

aws s3 cp "$FINAL_FILE" "s3://$BUCKET/$FILE_NAME" --region "$REGION" --only-show-errors \
  >/dev/null 2>&1 || fail "s3-upload"
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'edupilot-prod-*.sql.gz' -mmin +4320 -delete \
  || fail "local-retention"
log_line "OK $FILE_NAME $SIZE_BYTES" || fail "log-write"
