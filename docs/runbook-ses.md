# SES 시스템 메일 운영 런북 (#410, #471, #473)

2026-10-05 출시 후보 BE521 기준이다. 이전 V43 기반의 공통 메일 골격 이후 가입 확인·reset·탈퇴 완료 호출과 V54 durable outbox가 구현됐다. 현재 운영 확인과 최소 조회·수신 인수·기존 대기 작업 처리의 상세 기준은 [FE·메일·V60 운영 준비](launch-operational-readiness.md#2-ses-확인-근거와-최소-조회)다. source 준비를 실제 SES 설정·발송·수신 완료로 해석하지 않는다.

## 현재 source 경계

- SES는 SDK v2 `SendEmail`, 명시 `AWS_REGION`, 기존 DefaultCredentialsProvider를 사용한다. 저장소의 서울 리전/From 및 IAM placeholder는 실제 region/domain 인증·실행 역할 권한 증거가 아니다.
- LoggingEmailSender는 **모든 프로파일에서 메시지 종류만** 기록한다. 수신 주소·제목·text/HTML·token은 출력하지 않는다. `logging-...` 및 SENT는 실제 전달이 아니다.
- worker의 SES SDK 자동 재시도는 끄고 알려진 429만 제한 재시도한다. 결과 불명은 UNKNOWN이며 자동 재전송하지 않는다. 최대 시도는 3이지만 실제 실패 유형에 따라 1회에 종료할 수 있으므로 실패를 항상 attemptCount=2로 기대하지 않는다.
- 본문은 V54 outbox에 AES-GCM으로 일시 보관한다. terminal/expiry에서 제거한다. 메일 본문이 DB에 저장되지 않는다는 옛 V43 설명을 현재 source에 적용하지 않는다.
- 실제 발송 전 기존 암호화 key/JWT 기반 HKDF 관리 버전을 유지한다. 키·credential을 읽거나 출력하지 않는다.
- prod의 logging 금지를 보존하며 기존 예외 설정으로 실제 발송·가입 확인을 우회하지 않는다. provider 변경/재생성·IAM/DNS/sandbox 신청은 별도 운영 작업이다.

## 운영자가 확인할 증거

[allowlist env 조회와 SES GET 2개](launch-operational-readiness.md#2-ses-확인-근거와-최소-조회)로 provider/enabled/region/from/base URL, SendingEnabled·sandbox/quota·identity/DKIM을 확인한다. 앱 실행 역할의 SendEmail 권한과 container credential 접근도 비밀값 없는 운영 증거가 필요하다. operator CLI의 성공을 앱 권한으로 대체하지 않는다. AccessDenied/미설정은 미확인으로 남기고 이번 준비 작업에서 권한을 늘리지 않는다.

사용자 확정 수신 주소는 **1개**이며 원문은 비공개 인계 자료만 사용한다. 주소 선택은 실제 발송 승인이 아니다. 실제 수신 인수는 별도 승인된 inbox/건수/비용/fixture 범위로 진행한다. 최소 가입 확인 1건 또는 재발급 포함 2건을 제안하며 password-reset/탈퇴 연결 전체는 4건, 추가 Google 신규 확인까지는 5건이다. 현재 실행은 0건이다. TEST/simulator는 inbox/confirm 인수를 대신하지 않는다.

가격·현재 요금제·추가 기능 여부는 [공식 SES 가격](https://aws.amazon.com/ses/pricing/)과 운영자 확인을 대조한다. 기존 서울 sandbox 한도나 무료 구간을 고정 값으로 가정하지 않는다.

## queued 작업과 긴급 중지

- `EDUPILOT_MAIL_ENABLED=false`는 새 요청을 DISABLED 이력으로 남기고 새 발송을 막지만 이미 SES가 수락했거나 진행 중인 요청을 회수하지 않는다. 실제 변경/재생성은 승인 운영자가 수행한다.
- V54 이전 QUEUED metadata는 `LEGACY_PAYLOAD_UNAVAILABLE` 실패가 된다. 과거 시도 quota 예약과 SENT/FAILED/RATE_LIMITED 이력을 보존한다.
- READY/RETRY는 worker가 **현재 provider**로 회수한다. stored query 본문은 fragment 코드 배포만으로 바뀌지 않는다. Logging SENT를 SES로 재발송하지 않는다.
- source의 수신자당 5회/시간·KST 전체 500회/일 quota는 시험별 receiver allowlist나 1/2건 hard cap이 아니다. signup pause도 다른 producer를 멈추지 않는다. 대기 건·다른 producer·시험 격리 방침을 먼저 결정한다.
- `readonly-counts.sql`은 본문/주소/token 없이 상태·시도·due/expiry·in-flight·quota 집계만 제공한다. 테이블 부재를 0건으로 계산하지 않는다.
- 반송·불만 자동 처리와 실제 provider 수신, 운영 worker 재시작, 운영 backup/restore는 합성 source 테스트의 완료 범위에 포함되지 않는다.

[durable outbox/재시작 처리](mail-outbox.md), [가입 이메일 계약](email-verification.md), [V60 보존 복구](launch-operational-readiness.md#4-v60-정리-hook을-보존하는-복구)를 함께 확인한다.
