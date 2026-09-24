# SES 시스템 메일 운영 런북 (#410)

이 PR은 공통 발송 기반만 설치합니다. 비밀번호 재설정·이메일 인증·알림의 **실제 호출 연결은 후속 이슈**입니다. 서버·AWS 적용은 운영자가 수행합니다. V43 적용 전 prod DB 스냅샷을 생성합니다.

## 1. SES 인증과 권한 (서울 리전)

1. AWS SES `ap-northeast-2` > Verified identities에서 `uteum.com` 도메인을 생성하고 Easy DKIM을 켭니다. Route 53이 같은 계정의 호스팅 영역이면 제공되는 DNS 레코드 게시 기능을 사용하고, 그렇지 않으면 SES가 제시한 DKIM CNAME 3개를 실제 권한 DNS에 수동 등록합니다. Verified 상태를 확인합니다. 발신 주소 `no-reply@uteum.com`은 검증된 도메인에 포함되지만, 콘솔에서 발신 주소도 확인합니다. MAIL FROM 하위 도메인은 선택 사항입니다.
2. `infra/iam/ec2-ses-policy.json`의 `<AWS_ACCOUNT_ID>`를 실제 계정 ID로 치환하고 `Ai-Tutor-EC2-SSM-Role`에 인라인 정책으로 추가합니다. `ses:FromAddress`를 `no-reply@uteum.com`으로 한정합니다. AWS 액세스 키를 환경변수나 파일에 넣지 않습니다.
3. SES sandbox에서는 검증된 수신 주소/도메인 또는 mailbox simulator로만 발송할 수 있습니다. dev 실발송 리허설에 쓸 팀원 메일을 Verified identities에서 별도 검증하거나 simulator를 사용합니다.
4. SES > Account dashboard에서 production access를 신청합니다. 용도는 비밀번호 재설정·이메일 인증·서비스 알림의 transactional mail, 예상 일 100통 미만으로 기재합니다. AWS 문서상 첫 응답 목표는 보통 24시간이지만 심사 결과·완료 시간은 보장되지 않습니다. **prod 수신자 발송은 해제 승인과 발송 한도 확인 후** 켭니다.

참고: [SES 도메인 검증](https://docs.aws.amazon.com/ses/latest/dg/creating-identities.html), [sandbox/production access](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html), [From 주소 IAM 제한](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html).

## 2. dev → SES 테스트 → prod

1. dev 배포 전에 V43이 포함됐는지 확인하고 `EDUPILOT_MAIL_ENABLED=true`, `EDUPILOT_MAIL_PROVIDER=logging`, `EDUPILOT_MAIL_FROM=no-reply@uteum.com`, `EDUPILOT_MAIL_BASE_URL=https://dev.uteum.com`, `AWS_REGION=ap-northeast-2`를 설정합니다. `docker compose up -d main-service`로 재생성합니다(`restart`만으로 새 환경변수가 반영되지 않음).
2. ADMIN으로 `POST /api/admin/mail/test`에 검증 가능한 수신 주소를 보내고 반환된 `deliveryId`를 `GET /api/admin/mail/deliveries`에서 찾습니다. dev/test의 logging provider는 본문을 INFO 로그에 출력하므로 실토큰·실사용자 정보를 넣지 않습니다. `SENT`, `attemptCount=1`, `logging-...` provider ID를 확인합니다.
3. `EDUPILOT_MAIL_PROVIDER=ses`로 바꿔 재생성하고 샌드박스에서 검증된 팀원 수신자 또는 SES simulator에 **한 통만** 보냅니다. `SENT`, SES message ID를 확인합니다. 실패 시 `FAILED`, `attemptCount=2`, 민감값이 제거된 `errorSummary`를 확인합니다. `EDUPILOT_MAIL_ENABLED=false`면 호출해도 이력만 `FAILED`/`DISABLED`가 됩니다.
4. prod에서는 SES 샌드박스 해제와 검증된 identity, IAM 권한을 확인하고 `EDUPILOT_MAIL_ENABLED=true`, `EDUPILOT_MAIL_PROVIDER=ses`, `EDUPILOT_MAIL_BASE_URL=https://www.uteum.com`, `AWS_REGION=ap-northeast-2`를 설정합니다. `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d main-service`로 재생성합니다. prod compose 오버라이드는 provider 기본값을 `ses`로 둡니다. `logging`을 명시하면 기동 WARN과 함께 실제 발송이 되지 않으며, 본문은 prod 로그에 나오지 않습니다.
5. prod 배포 후 관리자 테스트 1통으로 발송·이력 확인. 메일 내용은 DB에 저장되지 않으며, SES `SENT`는 API 수락을 뜻하지 최종 수신함 배달 보장은 아닙니다.

## 3. 운영 점검

- 수신자당 직전 1시간 5통, 전체 KST 날짜당 500통이 상한입니다. 초과 행은 `RATE_LIMITED`이고 SES 호출은 없습니다. 샌드박스의 AWS 자체 한도(현재 문서 기준 24시간 200통/초당 1통)는 앱 상한과 별개입니다.
- `QUEUED`가 오래 지속되거나 `FAILED`가 증가하면 executor/DB/SES 장애, IAM, identity, sandbox/한도, region을 순서대로 확인합니다. 앱 이력에는 본문이 없으며 prod 로그에도 본문·키를 남기지 않습니다.
- 반송·불만·수신거부 자동 처리는 이번 범위 밖입니다. SES 콘솔의 발송·반송 지표를 수동 확인하고 필요한 자동화는 별도 이슈로 진행합니다.
- 중지: `EDUPILOT_MAIL_ENABLED=false`로 변경 후 컨테이너 재생성. 이미 SES가 수락한 메일은 회수되지 않습니다.
