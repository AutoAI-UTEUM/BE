# #443 SSE 연결 수명 주기·턴 시작 경합 검증

작성일: 2026-09-28. 범위: Spring main-service 내부 상태 보호·계측.
외부 SSE/AI 계약, DB, 모델·쿼터·재시도 정책은 변경하지 않는다.
로컬 구현·자동 테스트 기록이며 dev/prod 배포·현장 원인 확정 기록이 아니다.
관련 이슈: [#443](https://github.com/AutoAI-UTEUM/BE/issues/443).

## 1. 근거와 미확정 사항

- 운영 분석의 main/AI 이미지 SHA: `9aa6cb67b444eafaedde0f17da08f89da99baef5`.
- 구현 기준 develop: `d48c91a` (PR #442 이후).
- 첨부 분석에서 읽었던 `a4a5d5b`도 비교했다. 두 SHA 각각에서 기준 develop까지
  `SessionStreamService`, `SessionStreamConnection`, `SessionTurnService`,
  `AiStreamCancellation` 4개 파일의 diff는 없다. 운영 Git 객체를 fetch하여
  확인했으며 해당 경로의 선행 수정은 없었다.
- 세션 573의 84ms·79ms 실패가 아래 BE 경합 때문이었다는 것은 **미확정**이다.
  당시 connectionId·종료 사유가 없어 Wi-Fi 단절, FE abort/교체, callback 등을
  구분할 수 없다. 이번 작업은 재현 가능한 코드 경합을 막고 다음 장애를 계측한다.

## 2. 재현된 결함과 보호 방식

기존 코드에서 실행 순서를 latch로 강제하면 다음이 가능했다.

```text
connect(B): A.isRunning() = false ────── A.replaceIdle() → B 등록 성공
beginTurn:                     A.begin(cancellation) = true
```

기존 `turnStartedAfterIdleCheckCannotBeReplaced` 재현 테스트는 1건 실패했다.
`TURN_IN_PROGRESS`를 기대했지만 연결이 성공하여
`Expecting code to raise a throwable`가 발생했다. 타이밍 sleep이나 실제 xAI는
사용하지 않았다. 수정 후 테스트는 원자 전이의 직전 경계를 latch로 제어한다.

현재 전이는 다음과 같다.

| 먼저 확정된 전이 | 후속 처리 |
| --- | --- |
| A의 begin | A 유지, B 등록은 `TURN_IN_PROGRESS(409)` |
| B의 교체 예약·등록 | A는 CLOSED, ready 성공 후 다음 begin은 B 선택 |
| B 등록 후 ready 미완료/실패 | B에 AI 스트림 연결 없음, 기존 JSON fallback |
| close가 begin보다 먼저 확정 | begin 실패, cancellation 연결 없음 |
| begin이 close보다 먼저 확정 | close가 그 cancellation을 캡처하여 해당 상류만 취소 |

- 연결 선택·begin은 `ConcurrentHashMap.computeIfPresent`, 교체 예약·등록은
  `compute`로 세션 키에 대해 원자적으로 처리한다.
- 연결 내부 `lifecycleLock`은 상태·cancellation·heartbeat 참조를 함께 갱신하는
  짧은 구간에만 사용한다. close는 자원을 로컬 `CloseWork`로 분리하고 참조를
  비운다. 같은 구간에서 첫 종료 이유를 고정한다.
- **Map → lifecycleLock** 구간에서는 emitter 전송·callback 호출·상류 취소·
  cleanup을 하지 않는다. emitter send monitor를 Map 안에서 잡지 않는다.
  callback은 lifecycleLock을 놓은 뒤 cleanup을 수행하므로 역방향 교착이 없다.
- DB 조회, SSE I/O, AI 호출은 Map/lifecycleLock 밖이다. 전역 synchronized나
  장기 DB 트랜잭션은 추가하지 않았다. 다른 세션이 느린 ready 전송 때문에
  대기하지 않는 테스트를 포함했다.
- 별도 세션별 lock Map은 없다. lifecycleLock은 연결 객체와 함께 수명을 끝낸다.
  heartbeat는 종료 시 취소하며, 종료 뒤 예약된 작업도 즉시 취소한다.
- `connections.remove(sessionId, connection)`의 기대 객체 일치 조건을 유지하여
  A의 지연 callback이 B를 지우거나 B의 cancellation을 취소하지 못하게 한다.
- ready 실패·실제 단절 이후 새 요청은 새 cancellation 객체를 사용한다.
  `AiStreamCancellation` 자체의 사용자 취소/단절 구분·body bind 보호는 변경하지 않았다.

## 3. 로그 계약 (BE 내부)

| 메시지 | 주요 필드 |
| --- | --- |
| `Session SSE connection registration` | sessionId, connectionId, previousConnectionId, previousState, REGISTERED/REJECTED, connectionTraceId, connectionCreatedAt, occurredAt |
| `Session SSE ready delivery` | 연결 상관 필드, readyResult=SENT |
| `Session turn transport selected` | sessionId, requestId, turnTraceId, connectionId/null, transport=SSE/JSON, reason, occurredAt |
| `AI turn attempt started/failed` | sessionId, requestId, connectionId/null, turnId, attempt, trace; 실패는 분류·안정 오류 코드 |
| `Session SSE connection closed` | 연결·턴 상관 필드, reason, wasRunning, upstreamCancelRequested, upstreamCancelled, userCancelled, lifetimeMs, occurredAt |
| `Session SSE event delivery failed` / `terminal delivery` | event, deliveryResult, 안전한 errorType; ready 실패는 readyResult=FAILED |
| `Session SSE turn cancellation requested` | 저장된 턴 상관 필드, reason=USER_CANCELLED |
| `SSE completion failed after turn persistence` | sessionId, requestId, connectionId, deliveryPhase=AFTER_PERSISTENCE, errorType |

선택 이유: `READY_CONNECTION`, `NO_CONNECTION`, `OWNER_MISMATCH`,
`CONNECTION_CLOSED`, `READY_NOT_SENT`, `TURN_IN_PROGRESS`.
실행 중 거부는 transport=null이며 JSON 실행을 의미하지 않는다.

종료 이유: `COMPLETED`, `APPLICATION_ERROR`, `IDLE_REPLACED`,
`READY_SEND_FAILED`, `STATUS_SEND_FAILED`, `CONTENT_SEND_FAILED`,
`HEARTBEAT_SEND_FAILED`, `EVENT_SEND_FAILED`, `EMITTER_TIMEOUT`, `EMITTER_ERROR`,
`EMITTER_COMPLETION`, `SERVICE_SHUTDOWN`, `REGISTRATION_REJECTED`.
`EMITTER_COMPLETION`은 callback 관측이지 사용자 네트워크 단절 확정이 아니다.
명시적 사용자 취소는 별도 신호 로그와 userCancelled로 구분한다.
upstreamCancelled는 취소 신호 상태이며 AI 서버의 실제 작업 중단 완료 증거가 아니다.

연결 trace·생성 UTC 시각은 생성 시 저장하고, 턴 trace·requestId는 begin 시,
turnId·attempt는 실제 AI 시도 직전 연결한다. callback/heartbeat는 현재 MDC를
재사용하지 않는다. 시도가 없었다면 turnId/attempt는 null이다.
예외 클래스·분류만 기록하며 본문·학생 답안·PDF·키·토큰·쿠키·인증 헤더는
기록하지 않는다. content delta 성공마다 로그를 만들지 않는다.

테스트의 상관 로그 형태(식별자 치환·필드 발췌, 운영 실측이 아님):

```json
{"message":"Session SSE connection registration","sessionId":100,"connectionId":"A","result":"REGISTERED","connectionTraceId":"connect-A"}
{"message":"Session SSE ready delivery","connectionId":"A","readyResult":"SENT"}
{"message":"Session turn transport selected","sessionId":100,"requestId":"R","connectionId":"A","turnTraceId":"turn-R","transport":"SSE","reason":"READY_CONNECTION"}
{"message":"AI turn attempt started","sessionId":100,"requestId":"R","connectionId":"A","turnId":"T1","attempt":1}
{"message":"AI turn attempt failed","requestId":"R","connectionId":"A","turnId":"T1","attempt":1,"retryable":true}
{"message":"AI turn attempt started","sessionId":100,"requestId":"R","connectionId":"A","turnId":"T2","attempt":2}
{"message":"Session SSE connection closed","connectionId":"A","requestId":"R","turnId":"T2","attempt":2,"connectionTraceId":"connect-A","turnTraceId":"turn-R","reason":"COMPLETED","wasRunning":true,"upstreamCancelled":false}
```

terminal 전송이 실패해도 첫 종료 reason은 바꾸지 않고
`event=completed, deliveryResult=FAILED`를 별도로 남긴다. 최종 저장 뒤 전달
실패는 턴 실패로 바꾸지 않으며 같은 requestId 재요청은 기존
`TURN_ALREADY_PROCESSED` 방어로 추가 AI 실행·메시지 저장을 막는다.

## 4. 자동 검증

기존 교착 방지 테스트 `cleanupAndConcurrentConnectCompleteWithoutDeadlock`를
보존했다. latch 기반 교체/begin, 두 connect/ready, begin/close 양쪽 순서,
ready·status·content·heartbeat 실패, timeout/error/completion callback,
terminal 이후 반복 callback, 자원 정리, 새 연결 복구, 권한/fallback/409,
저장 후 전달 실패와 동일 requestId, 두 AI 시도 로그 연결·민감 본문 부재를 검증한다.
실제 xAI·운영 DB·네트워크 단절은 사용하지 않는다.

실행 (main-service):

```bash
./gradlew test --no-daemon \
  --tests 'io.edupilot.session.SessionStreamServiceTest' \
  --tests 'io.edupilot.session.SessionStreamConnectionTest' \
  --tests 'io.edupilot.session.SessionTurnServiceTest' \
  --tests 'io.edupilot.session.SessionApiContractTest' \
  --tests 'io.edupilot.session.TurnClaimServiceTest'
./gradlew build --no-daemon
git diff --check
```

- 기존 결함 재현: 테스트 1건 실패 (수정 전, 위 순서).
- 수정 후 대상 5개 클래스: 83건, 실패·오류·스킵 0. 지연된 A cleanup을 B 턴 시작
  이후 재개하는 추가 회귀 테스트를 포함한다.
- 최종 `./gradlew build --no-daemon --console=plain`: BUILD SUCCESSFUL (5분 29초).
  218개 suite / 1,126건 / 실패 0 / 오류 0 / 스킵 1 (`AiClientLiveTest`).
  실서비스 AI를 사용하지 않는 전체 회귀 검증이다.
- 최종 `git diff --check`: 통과. 새 문서·fake emitter의 trailing whitespace도
  별도로 검사하여 없음을 확인했다.
- 최초 sandbox 전체 build는 Gradle 다운로드 권한 제한(getsockopt)으로 실행
  단계에서 실패했다. 테스트 실패와 구분하며 승인된 동일 명령으로 재검증하여
  위 성공 결과를 확인했다.
- 원격 CI·develop merge·dev/prod smoke는 로컬 테스트 통과로 대체하지 않는다.

변경 파일:

- `SessionStreamService`: 원자 교체·선택, 준비 중 연결 fallback, 등록/경로 로그.
- `SessionStreamConnection`: 상태/자원 전이 보호, 첫 종료 이유 고정, callback 상관 로그.
- `SessionTurnService`: requestId 전달, AI 시도 메타데이터, 이미 취소된 연결의 호출 방지,
  저장 뒤 전달 오류 구분. 저장·claim·재시도 조건은 유지.
- 위 서비스의 단위 테스트 3개와 `ControllableSseEmitter` fake; 기존
  `TurnAiUsageIntegrationTest`의 내부 beginTurn 시그니처 픽스처 1곳 갱신.
- `api-spec.md`, `screen-api-map.md`, 이 검증 기록.

구현 SHA와 원격 CI 결과는 Git 전달·PR 기록에서 확인한다. 이 문서의 검증
수치는 로컬 자동 테스트 결과이며 원격 CI·병합 완료를 의미하지 않는다.
기존 사용자 수정인 `docs/README.md`, `docs/decisions.md`는 이 작업과 분리한다.

## 5. FE 전달·배포 후 확인 (아직 미수행)

- API·SSE payload 변경 없음. connectionId를 FE가 보내는 변경은 이번 범위가 아니다.
- 현재 연결 시도의 ready 수신/POST 시각을 함께 확인한다. BE SENT만으로 FE 수신을
  판단하지 않는다. 이전 reader/callback은 새 연결 세대의 상태를 변경하지 않게 한다.
- 저장 후 SSE 전달 실패에는 세션 상세·메시지 재조회로 수렴한다. 복원된 완료
  메시지가 한 번에 보이는 것만으로 새 실패·재실행 필요로 판정하지 않는다.
- [ ] BE·FE CI 통과와 develop merge 확인.
- [ ] dev에서 Wi-Fi 단절·복구 T1/T2 후 새 질문 두 번이 추가 수동 재시도 없이
  스트리밍되는지 양측 로그와 함께 확인.
- [ ] 재현 시각, BE/FE 배포 SHA, 연결 시도 ready 수신, requestId·connectionId·
  turnId/attempt·종료 reason 기록.
- [ ] 운영 종료 원인 확정은 새 로그 근거가 확보된 뒤 수행.

마이그레이션·환경 변수·인프라 증설 없음. 전체 T1–T5 재시험이나 기존 캐시
벤치마크 40회는 이 작업의 선행 조건이 아니다.
