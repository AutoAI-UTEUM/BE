# 운영 DB 자동 백업 및 복구 런북 (#408)

대상은 prod MySQL의 `edupilot` 데이터베이스다. 백업은 매일 04:00 KST에 서비스 중단 없이
`mysqldump --single-transaction --routines --triggers`로 생성한다. 이 런북의 서버·AWS
명령은 **운영자가 직접 실행**한다. 저장소 반영만으로 백업이 활성화되지는 않는다.

목표 복구 시간은 20분이다. DB 크기·네트워크·마이그레이션 시간에 따라 달라지므로 분기별
리허설에서 실제 소요 시간을 기록한다. DB 백업에는 업로드 PDF가 포함되지 않는다.

## 1. 최초 설치: AWS (관리자 자격증명 사용)

1. 서울(`ap-northeast-2`) 리전에 `uteum-db-backup` 버킷을 만든다. S3 콘솔에서 퍼블릭
   액세스 차단 4개 모두 ON, 객체 소유권 **버킷 소유자 적용(Bucket owner enforced)**,
   기본 암호화 **SSE-S3**, 버저닝 **비활성화**를 확인한다. 수명주기 규칙은 버킷 전체
   객체에 대해 생성 후 **30일 만료**로 설정한다. 별도 KMS CMK는 만들지 않는다.
2. 관리자 로컬 CLI로 설정을 확인한다.

   ```bash
   aws s3api get-public-access-block --bucket uteum-db-backup --region ap-northeast-2
   aws s3api get-bucket-encryption --bucket uteum-db-backup --region ap-northeast-2
   aws s3api get-bucket-ownership-controls --bucket uteum-db-backup --region ap-northeast-2
   aws s3api get-bucket-versioning --bucket uteum-db-backup --region ap-northeast-2
   aws s3api get-bucket-lifecycle-configuration --bucket uteum-db-backup --region ap-northeast-2
   ```

   새 버킷의 버저닝 조회에는 `Status`가 없어야 한다. `Suspended`라면 과거 버전이
   남았을 수 있으므로 버전 목록을 별도로 조사한다. 수명주기 응답에서 전체 객체에
   대한 `Expiration.Days=30`을 확인한다.
3. 기존 CloudWatch 경보의 SNS 토픽 ARN을 확인한다. 아래 조회 결과의 `AlarmActions`와
   SNS 콘솔의 구독(이메일 **Confirmed**)을 대조한다. 새 토픽을 만들지 않는다.

   ```bash
   aws cloudwatch describe-alarms --region ap-northeast-2 \
     --query 'MetricAlarms[].{Name:AlarmName,Actions:AlarmActions}' --output table
   ```

4. `infra/iam/ec2-s3-backup-policy.json`의 **로컬 적용용 사본**에서
   `000000000000:REPLACE_WITH_EXISTING_ALARM_TOPIC`을 3에서 확인한 실제 ARN으로
   교체한다. `Ai-Tutor-EC2-SSM-Role` 역할에 인라인 정책으로 붙인다. EC2에는 별도 AWS
   액세스 키를 배포하지 않는다.

   ```bash
   aws iam put-role-policy --role-name Ai-Tutor-EC2-SSM-Role \
     --policy-name EduPilotDbBackupWriteOnly \
     --policy-document file://ec2-s3-backup-policy.prod.json
   aws iam get-role-policy --role-name Ai-Tutor-EC2-SSM-Role \
     --policy-name EduPilotDbBackupWriteOnly
   ```

   정책은 버킷 객체에 대한 `s3:PutObject`와 해당 토픽 `sns:Publish`만 허용한다.
   `GetObject`·`ListBucket`·`DeleteObject`가 없으므로 서버 역할로는 복원 파일을
   조회·다운로드·삭제할 수 없다. 단, **PutObject만으로도 같은 키를 덮어쓸 수 있어
   침해 시 백업 불변성까지 보장하지는 않는다**. 복원 다운로드는 별도 관리자 로컬
   자격증명으로 수행한다.

## 2. 최초 설치: prod 서버

1. 저장소의 `scripts/db-backup.sh`를 `/opt/edupilot/scripts/db-backup.sh`에 배치하고
   실행 권한을 `700`으로 설정한다. Docker 접근과 `/var/log` 기록이 가능해야 하므로
   아래 cron은 root로 실행한다. 현재 Compose 프로젝트와 `.env`가 `/opt/edupilot`에
   있는지 먼저 확인한다. 스크립트는 base `docker-compose.yml`의 실행 중인 `mysql`
   서비스에 `exec`하며, 앱 이미지는 재생성하지 않는다.

   ```bash
   cd /opt/edupilot
   command -v docker aws gzip find
   sudo chmod 700 scripts/db-backup.sh
   sudo mkdir -p -m 700 /opt/edupilot/backup
   sudo docker compose --env-file .env ps mysql
   ```

2. root crontab에 기존 경보 토픽 ARN을 넣는다. ARN과 리전은 비밀이 아니지만 AWS 키나
   DB 비밀번호는 crontab에 넣지 않는다. 스크립트 안에도 비밀번호가 없으며 MySQL
   컨테이너 내부의 `MYSQL_ROOT_PASSWORD`만 `MYSQL_PWD`로 전달한다. `-p비밀번호`
   형태로 프로세스 인자에 노출하지 않는다.

   ```bash
   date '+%Z %z'
   sudo crontab -e
   ```

   서버 TZ가 KST면 아래 **첫째 작업 줄만**, UTC면 **둘째 작업 줄만** 등록한다. UTC 19:00은
   다음 날 KST 04:00이다. `REPLACE_WITH_EXISTING_ALARM_TOPIC`은 실제 토픽명으로
   바꾼다. 적용 후 `sudo crontab -l`로 중복 등록이 없는지 확인한다.

   ```cron
   PATH=/usr/local/bin:/usr/bin:/bin
   0 4 * * * AWS_REGION=ap-northeast-2 EDUPILOT_DB_BACKUP_SNS_TOPIC_ARN=arn:aws:sns:ap-northeast-2:ACCOUNT_ID:REPLACE_WITH_EXISTING_ALARM_TOPIC /opt/edupilot/scripts/db-backup.sh
   0 19 * * * AWS_REGION=ap-northeast-2 EDUPILOT_DB_BACKUP_SNS_TOPIC_ARN=arn:aws:sns:ap-northeast-2:ACCOUNT_ID:REPLACE_WITH_EXISTING_ALARM_TOPIC /opt/edupilot/scripts/db-backup.sh
   ```

3. `/etc/logrotate.d/edupilot-backup`을 다음 내용으로 설치한다. 로그는
   `/var/log/edupilot-backup.log`에 append되고 이 파일에서 30일 보관한다.

   ```text
   /var/log/edupilot-backup.log {
       daily
       rotate 30
       compress
       missingok
       notifempty
       create 0600 root root
   }
   ```

4. root로 첫 수동 실행한다. 성공하면 `OK <파일명> <압축파일 바이트 수>`가 로그에 한 줄
   남고, `.db-backup-*` 임시 파일은 없어야 한다. 로컬 완성본은 최근 3일치만 유지된다.
   S3 조회는 **관리자 로컬 CLI**로 한다(서버 역할에는 List/Get 권한 없음).

   ```bash
   sudo env AWS_REGION=ap-northeast-2 \
     EDUPILOT_DB_BACKUP_SNS_TOPIC_ARN='arn:aws:sns:ap-northeast-2:ACCOUNT_ID:TOPIC' \
     /opt/edupilot/scripts/db-backup.sh
   echo "$?"
   sudo tail -n 1 /var/log/edupilot-backup.log
   sudo find /opt/edupilot/backup -maxdepth 1 -name '.db-backup-*' -print
   ```

   ```bash
   aws s3 ls s3://uteum-db-backup/ --region ap-northeast-2
   ```

   덤프 종료코드, **압축 파일 크기 > 1 MiB**, `gzip -t`, S3 업로드 종료코드가 모두
   성공해야 `OK`다. Put-only 정책상 서버에서 S3 `head-object` 검증은 할 수 없다.
   실제 DB가 1 MiB보다 작으면 이 의도적 최소 크기 가드로 실패한다. 임의로 가드를
   우회하지 말고 요구사항과 실제 DB 크기를 확인한다.
5. 첫 성공과 **다른 KST 분**에, 테스트로 존재하지 않는 버킷명을 한 번 지정한다.
   같은 분에 재실행하면 파일명 충돌이 먼저 발생해 업로드 실패 경로를 테스트하지 못한다.
   종료코드 1, 로그 `FAIL stage=s3-upload`, 기존 SNS 구독 메일 수신을 확인한다.
   실패한 로컬 완성본은 남으므로 테스트 후 3일 보관 정책에 따라 정리된다.

   ```bash
   sudo env AWS_REGION=ap-northeast-2 \
     EDUPILOT_DB_BACKUP_SNS_TOPIC_ARN='arn:aws:sns:ap-northeast-2:ACCOUNT_ID:TOPIC' \
     EDUPILOT_DB_BACKUP_BUCKET='uteum-db-backup-intentional-failure' \
     /opt/edupilot/scripts/db-backup.sh
   echo "$?"
   sudo tail -n 2 /var/log/edupilot-backup.log
   ```

6. 7일 후 관리자 로컬에서 날짜별 S3 파일 **7개 연속**, 각 크기 > 1 MiB를 확인하고
   이슈 #408의 운영 완료 조건을 체크한다. 30일 수명주기 때문에 이후에는 최근 30일
   범위만 남는다. cron 설정만으로 완료 처리하지 않는다.

## 3. prod 복구 (목표 20분)

복구는 승인된 운영자가 수행한다. **대상 호스트와 DB가 prod인지 재확인**하고,
서비스 쓰기를 멈춘 뒤 진행한다. 필요한 것은 관리자 로컬 AWS 읽기 권한·SSH 권한과
서버의 Docker 권한이다. 파일 경로·계정·현재 배포 SHA는 실제 환경 값으로 바꾼다.

1. 관리자 로컬에서 S3 객체 목록을 보고 **복원할 KST 시각의 파일명**을 고른다.
   크기를 확인해 내려받고 압축 무결성을 검사한다. 서버의 Put-only 역할로는 이 단계가
   불가능하다. S3 객체에 접근 가능한 관리자 로컬 자격증명을 사용한다.

   ```bash
   umask 077
   aws s3 ls s3://uteum-db-backup/ --region ap-northeast-2
   aws s3 cp s3://uteum-db-backup/edupilot-prod-YYYYMMDD-HHMM.sql.gz \
     ./edupilot-prod-YYYYMMDD-HHMM.sql.gz --region ap-northeast-2
   chmod 600 ./edupilot-prod-YYYYMMDD-HHMM.sql.gz
   gzip -t ./edupilot-prod-YYYYMMDD-HHMM.sql.gz
   scp -p ./edupilot-prod-YYYYMMDD-HHMM.sql.gz SSH_USER@SERVER:~/
   ```

2. 서버에서 현재 배포된 SHA를 확인하고 Compose 함수를 준비한다. 이후 **같은 셸**에서
   실행한다. 전송한 파일은 SSH 사용자의 홈에서 root 전용 백업 디렉터리로 옮긴다.
   `SSH_USER`는 실제 SSH 계정으로 바꾼다. `main-service`·`ai-service`만 중지하고
   `mysql`은 유지한다. 쓰기 유입이 중단되었는지 확인한다.

   ```bash
   ssh SSH_USER@SERVER
   sudo -i
   cd /opt/edupilot
   install -m 600 /home/SSH_USER/edupilot-prod-YYYYMMDD-HHMM.sql.gz \
     /opt/edupilot/backup/edupilot-prod-YYYYMMDD-HHMM.sql.gz
   export ENVIRONMENT=prod TAG=REPLACE_WITH_CURRENT_DEPLOYED_SHA
   dc() { docker compose --env-file .env -f docker-compose.yml -f docker-compose.prod.yml "$@"; }
   dc ps
   dc stop main-service ai-service
   dc ps
   ```

3. **복원 직전 안전 스냅샷**을 로컬에 만든다. 실패·오복원 시 되돌릴 유일한 즉시
   복구본이므로 압축 무결성까지 확인한다. 이 파일은 자동 3일 정리 패턴과 이름을
   구분하고 별도 접근 제한을 유지한다. `pipefail`이 덤프 실패를 놓치지 않게 한다.

   ```bash
   umask 077
   set -o pipefail
   dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines --triggers edupilot' \
     | gzip -c > /opt/edupilot/backup/pre-restore-YYYYMMDD-HHMM.sql.gz
   gzip -t /opt/edupilot/backup/pre-restore-YYYYMMDD-HHMM.sql.gz
   ```

4. 내려받은 백업의 `gzip -t`를 서버에서도 확인한다. 이후의 DB 재생성은 **기존
   `edupilot` DB를 삭제**하므로 3의 안전 스냅샷 성공을 확인하기 전에는 실행하지
   않는다. 옛 덤프에 없는 신규 테이블까지 제거해 덤프 시점 상태로 맞춘다. 선택한
   백업을 복원하고 **파이프라인 종료코드 0**을 확인한다.

   ```bash
   gzip -t /opt/edupilot/backup/edupilot-prod-YYYYMMDD-HHMM.sql.gz
   dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -e "DROP DATABASE edupilot; CREATE DATABASE edupilot CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
   gunzip -c /opt/edupilot/backup/edupilot-prod-YYYYMMDD-HHMM.sql.gz \
     | dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root edupilot'
   ```

5. 앱을 다시 띄우기 전에 다음 5종 COUNT를 기록·비교한다. 백업 직후 운영자가 남긴
   덤프 시점 카운트 기록 또는 같은 아카이브를 격리된 dev DB에 복원해 얻은 기대값과
   비교한다. `mysqldump`와 별도 조회는 동일 트랜잭션이 아니므로, 가동 중 기록한
   원본 DB 카운트와 소량 차이가 날 수 있다. 차이가 크거나 테이블이 없으면 재기동
   전에 중단해 원인을 확인한다.

   ```bash
   dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -N -B edupilot -e "SELECT (SELECT COUNT(*) FROM classrooms) AS classrooms, (SELECT COUNT(*) FROM learning_sessions) AS learning_sessions, (SELECT COUNT(*) FROM chat_messages) AS chat_messages, (SELECT COUNT(*) FROM exam_submissions) AS exam_submissions, (SELECT COUNT(*) FROM users) AS users;"'
   ```

   출력 열 순서는 `classrooms`, `learning_sessions`, `chat_messages`,
   `exam_submissions`, `users`다.

6. `main-service`·`ai-service`를 재기동한다. `/api/health/ready`가 정상인지 확인하고
   main-service 로그에서 Flyway가 적용 완료 상태인지 확인한다. 덤프에는
   `flyway_schema_history`가 포함된다. 이미지가 덤프 시점 이후 migration을 포함하면
   기동 시 자동 적용되므로 **이미지 SHA와 DB 버전 호환성**을 먼저 확인한다. 호환되지
   않으면 무작정 옛 이미지나 migration을 재적용하지 않는다.

   ```bash
   dc up -d main-service ai-service
   curl --fail --silent --show-error https://www.uteum.com/api/health/ready
   dc logs --tail=200 main-service
   ```

7. 복원 후 이상이 있으면 앱을 다시 중지하고 3의 `pre-restore-*.sql.gz`로 **4의 DB
   재생성·복원 절차를 동일하게** 수행한 뒤 5의 카운트, 6의 헬스·Flyway 검증을
   반복한다. 안전 스냅샷과 내려받은 prod 덤프는 장애 기록과 보존 방침을 확인한 뒤
   운영자가 삭제한다. `docker compose down -v`는 사용하지 않는다.

## 4. dev 복원 리허설 (분기 1회)

1. 위 3-1 방식으로 prod 덤프를 관리자 로컬에서 받아 dev 서버에 전송한다. prod DB
   원문이므로 팀 내부 접근 제한만 허용하고 공개 공유·로그 첨부를 금한다.
2. dev의 `main-service`·`ai-service`를 멈추고 dev 원본 DB를 3-3과 같은 옵션으로
   먼저 덤프한다. `mysql`은 계속 실행한다. prod와 dev 호스트·Compose 프로젝트를
   두 번 확인한다.
3. dev에 3-4 방식으로 prod 덤프를 복원한 후 3-5의 5종 COUNT를 실행한다. 같은
   아카이브에서 기대한 카운트와 일치하는지 확인하고 실제 소요 시간을 기록한다.
   덤프 시점 prod 카운트 기록이 없으면 이 리허설 결과를 **해당 아카이브의 기대값**으로
   보존한다. 실행 중 prod를 별도 조회한 값과의 차이는 정확 일치로 오인하지 않는다.
4. dev는 검증 데이터뿐이면 그대로 둘 수 있지만, prod 개인정보가 불필요하게 남지
   않도록 **dev 원본 스냅샷으로 재복원**하는 쪽을 권장한다. 복구 후 dev 서비스 헬스와
   Flyway를 확인하고, dev 서버·관리자 로컬의 prod 덤프 임시 사본을 정리한다.

리허설 기록 (실행 후 운영자가 채움):

| 항목 | 기록 |
| --- | --- |
| 실행 일시 / 담당자 | 미실행 |
| 백업 파일명 / S3 크기 | 미실행 |
| 덤프 시점·복원 후 COUNT 5종 | 미실행 |
| 복원 소요 시간 / 20분 목표 달성 여부 | 미실행 |
| 헬스·Flyway 결과 / dev 원복 여부 | 미실행 |

## 5. 정기 점검

- 매월 관리자 로컬에서 `aws s3 ls s3://uteum-db-backup/ --region ap-northeast-2`로
  날짜 연속성·0바이트가 아닌 크기를 확인하고 점검 기록을 남긴다.
- 분기마다 dev 복원 리허설을 캘린더에 등록하고, 20분 목표 대비 시간을 기록한다.
- 백업 실패 SNS 메일이 오면 `/var/log/edupilot-backup.log`의 `FAIL stage=...`를
  확인한다. `mysqldump`, `gzip`, `size-check`, `gzip-integrity`, `s3-upload`,
  `local-retention` 등 실패 단계별로 원인을 조사한다. 알림 전송 자체가 실패하면
  `FAIL stage=sns-publish`가 로그에 남으므로 로그도 별도로 점검한다.
- 30일 S3 보관과 최근 3일 로컬 보관은 실수 복구 범위를 제한한다. 요구 RPO·보관
  기간이 달라지면 운영 정책 변경으로 별도 결정한다.
