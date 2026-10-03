# 메일 작업 영속화와 재시작 회수 (#473)

`feature/473-mail-recovery`는 메일 본문을 로그에서 제거하고, 암호화된 DB 작업을
커밋 뒤 발송하여 재시작·실행기 포화에 따른 메모리 작업 유실을 복구한다.
SES의 요청 수락은 수신함 도착 확인이 아니다. 실제 수신·IAM·운영 키 등록은 별도 검증이다.

## 상태와 재처리

| 작업 상태 | 처리 |
| --- | --- |
| READY | 커밋된 작업. DB 행 잠금과 새 fencing token으로 claim한다. |
| CLAIMED | 아직 provider 호출 전. lease 만료 시 READY로 회수한다. 이전 token은 발송할 수 없다. |
| SENDING | provider 호출 전에 별도 DB 트랜잭션으로 기록한다. 호출 중 DB 잠금을 유지하지 않는다. |
| RETRY | SES 429처럼 거절이 확정된 경우만 제한 재시도한다. |
| SENT | provider 수락 이력을 기록했다. 본문을 제거한다. |
| FAILED | 만료·복호화 실패·거절·시도 한도 초과 등. 본문을 제거한다. |
| UNKNOWN | SENDING 중 프로세스 종료, 통신 오류 또는 수락 후 이력 저장 실패. 본문을 제거하고 자동 재발송을 막는다. |

SES `SendEmail`에는 요청 멱등 키가 없으므로 통신 오류·5xx·발송 후 프로세스 종료의
수락 여부를 BE만으로 확정할 수 없다. 이를 성공으로 간주하거나 자동 재발송하지 않는다.
같은 fencing token의 실행이 뒤늦게 확정된 수락 ID를 가져온 경우만 UNKNOWN에서 SENT로
이력을 정정할 수 있다. SDK 내부 재시도는 `maxAttempts=1`로 제한한다.
[SES API](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html),
[SDK retry 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html).

회수는 기본 30초마다 만료 본문·lease 정리 최대 50개, 발송 대상 최대 50개를 각각 조회한다.
정리 조회에는 정상 READY 작업을 섞지 않으므로 발송 비활성 상태에서도 뒤쪽의 만료된
인증 본문을 제거한다. 발송 비활성 시에는 발송 대상 조회·provider 호출을 하지 않는다.
lease 2분, 확정된 429 재시도 간격 30초, 최대 provider 호출 3회다.
수신자별 최근 1시간 5건/전체 KST 일별 500건은 발송 직전의 영속 예약으로 적용한다.
`email_quota_lock` 단일 행을 잠근 짧은 READ_COMMITTED 트랜잭션에서
`email_send_reservations`를 확인·삽입하므로 여러 worker가 같은 한도를 공유한다.
메일 생성 시각·delivery ID 순서는 한도 계산에 쓰지 않는다. 같은 claim의 예약은 멱등이며
새 claim으로 재시도할 때마다 한 건을 예약한다. 예약 뒤 발송 전에 종료·만료된 경우도
예산을 돌려주지 않는 보수적 한도다. provider 호출 동안 DB 잠금을 잡지 않는다.
인증·비밀번호 재설정 본문은 기본 30분, 기타 본문은 24시간 안에만 발송 가능하다.
호출자는 `sendAsync(message, expiresAt)`으로 인증 토큰의 정확한 만료 시각을 넘길 수 있다.
본문 만료와 터미널 상태에서 payload는 NULL로 제거한다. 메타데이터 보존기간은 이 변경에서 정하지 않는다.

## 트랜잭션

`email_deliveries` 요청 이력은 REQUIRES_NEW 감사 트랜잭션으로 남긴다. 발송 가능한
`email_outbox` 삽입은 호출자 트랜잭션에 참여한다. 호출자 롤백 시 본문은 저장되지 않고
감사 이력만 `CALLER_TRANSACTION_ROLLED_BACK` 실패로 남는다. 발송은 커밋 뒤 시작한다.
본문 DB 삽입 실패는 호출자 트랜잭션 실패로 전파된다. provider 실패는 비동기로 이력에 기록한다.
커밋 직후 dispatch 전에 종료되거나 실행기가 포화되면 READY 작업이 남아 다음 회수에서 처리된다.

## 키와 로그

AES-256-GCM은 매번 무작위 nonce를 만들고 delivery ID를 인증된 추가 데이터에 묶는다.
선택 환경변수 `EDUPILOT_MAIL_OUTBOX_ENCRYPTION_KEY`는 Base64로 인코딩한 32바이트
별도 키다. 값이 없으면 기존 JWT secret에서 메일 전용 HKDF-SHA256 도메인으로 키를
유도한다. JWT 서명 키 자체를 AES 키로 쓰지 않는다. 유효하지 않은 키 설정은 기동 실패다.

여러 서버와 재시작 사이에는 같은 키가 필요하다. 별도 키를 쓰지 않는 상태에서 JWT secret을
바꾸거나 별도 키를 교체하면 이전 본문을 읽을 수 없다. 키 교체 전 미완료 작업을 비우거나
기존 키를 유지하는 운영 절차가 필요하다. 복호화 실패는 `PAYLOAD_UNREADABLE`로 종결되며
임의 새 본문 생성·성공 처리·재발송을 하지 않는다. 이 PR은 운영 키를 생성·등록하지 않는다.
운영에서 독립 키를 사용할지는 담당자가 결정하고 승인된 secret 관리 경로로 등록해야 한다.

LoggingEmailSender는 모든 프로파일에서 종류만 기록한다. 수신 주소·제목·text/HTML·토큰·
암호화 키는 기록하지 않는다. worker는 delivery ID와 오류 클래스 또는 정해진 오류 코드만
남기며 provider의 원문 예외 메시지는 이력·로그에 복사하지 않는다. prod logging 금지는 유지한다.

## Migration과 롤백

V54는 `email_outbox`와 상태/시도 수 제약, due/lease/expiry 인덱스, delivery FK의 ON DELETE CASCADE를
추가한다. `email_quota_lock`의 행 1을 migration에서 만들고, 없으면 발송 예약은 실패한다.
`email_send_reservations(delivery_id, claim_token, reserved_at, units)`에는 claim 고유 제약과
시간 인덱스, 양수 units 제약, delivery FK의 ON DELETE CASCADE가 있다. 새 예약의 units는 1이다.
기존 SENT 또는 attempt_count가 양수인 이력은 알려진 sent_at을 기준으로 초기 예산을 예약한다.
발송 시각이 없는 과거 시도는 migration 시각에 보수적으로 예약하며 실제 발송 증거로 취급하지
않는다. 과거 attempt_count를 units에 보존하므로 일부 환경은 migration 직후 시간/일 한도가
이미 소진될 수 있다. 배포 전 이 영향을 확인하고, 이력을 지우거나 한도 설정을 끄지 않는다.
기존 QUEUED 행에는 본문이 없으므로 `LEGACY_PAYLOAD_UNAVAILABLE` 실패로 표시한다.
기존 SENT/FAILED/RATE_LIMITED 이력은 유지한다. 마이그레이션은 비동기 작업 유실을 성공으로
포장하거나 과거 메일을 재발송하지 않는다. V53 뒤, 후속 인증 migration 전에 적용해야 한다.

긴급 발송 중지는 기존 `EDUPILOT_MAIL_ENABLED=false`를 쓴다. 이미 발송 중인 요청을 되돌릴 수는
없다. DB는 먼저 백업하고, V54를 적용한 뒤 구 버전으로 돌아가면 구 버전은 새 outbox를 회수하지
않는다는 한계가 있다. 테이블·감사 이력을 자동 삭제하는 down migration은 제공하지 않는다.
develop 반영은 DEV 컨테이너 교체와 Flyway 실행을 수반하므로 별도 배포 승인을 기다린다.

## 검증 경계

합성 키·합성 주소·mock provider로 중복 worker, lease 만료, 재시작 회수, 포화, 제한 재시도,
호출자 commit/rollback, provider 수락 후 이력 트랜잭션 실패, 본문 제거를 검증한다.
2시간 전 생성된 6건의 순차·동시 회수, 더 높은 ID의 기존 일 한도 예약,
수신자가 다른 동시 worker의 마지막 일 예산 경쟁, 같은 claim 예약 멱등성,
발송 비활성 상태의 정상 READY 50건 뒤 만료 인증 본문 정리도 검증한다.
실제 메일 수신·실운영 재시작·운영 백업 복구·학생 데이터·SES 권한 변경은 실행하지 않는다.
