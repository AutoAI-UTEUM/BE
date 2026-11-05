# Composer prediction FE 연동 계약

2026-11-04 로컬 BE 구현 기준. **미배포·기본 OFF**이며 FE 코드는 이번 작업에서 수정하지 않았다.
공통 JSON 명칭은 AI #570의 실제 `ComposerPredictionRequest/Response` v1.0을 따른다.
브라우저의 저장 메시지 선택 요청과 SSE 응답은 아래에 정의한 Spring 어댑터 계약이다.

## 경로와 호출 시점

```http
POST /api/sessions/{sessionId}/composer-prediction
Authorization: Bearer <기존 사용자 access token>
Content-Type: application/json
Accept: text/event-stream

{"sourceMessageId":123}
```

기존 `POST /api/sessions/{sessionId}/turns`가 성공한 뒤, 응답 `data.messages`의
마지막 저장된 AI 메시지 `id`를 선택한다. 해당 메시지는 `COMPLETED`인
`EXPLANATION` 또는 `QA`여야 한다. 화면의 입력칸이 비어 있고 추천을 허용한 때에만
별도로 1회 요청한다. 부분 delta·quiz preview에는 저장된 source ID가 없으므로 호출하지 않는다.
`completed` SSE만 보고 앞서 요청하면 원래 turn의 claim 정리가 아직 끝나지 않았을 수 있다.
turn POST 응답까지 기다리며, 사전 gate가 null을 반환해도 자동 재요청하지 않는다.

FE가 보내는 필드는 양의 정수 `sourceMessageId` 하나다. 세션·메시지 소유권과 최신성은
서버가 검사한다. `messages`, `learningActivity`, PDF, 정답, 문맥 버전과 내부 토큰은
브라우저 요청에 포함하지 않는다. 이 값들은 서버가 만들거나 제외한다.

## 응답: fetch 기반 POST SSE

```text
:heartbeat

event: prediction
data: {"schemaVersion":"1.0","sourceTurnId":"message-123","contextVersion":"<server-generated-sha256>","suggestion":"다른 예시로 설명해 줄 수 있어?","warnings":[],"sourceMessageId":123}

```

`contextVersion` 예시는 자리표시자이며 실제 값은 서버가 계산한 64자리 SHA-256이다.
하트비트 주석을 무시하고 `prediction` 이벤트의 `data`만 JSON으로 읽는다. 한 요청에는
최대 한 결과가 오고 연결이 종료된다. UTF-8/줄/이벤트 경계가 네트워크 chunk와 일치한다고
가정하지 않는다. 기존 fetch-SSE 파서를 재사용한다. POST 본문과 Bearer 헤더가 필요하므로
브라우저의 GET `EventSource`를 그대로 사용하는 방식은 맞지 않는다.

```ts
type ComposerPredictionSelectRequest = { sourceMessageId: number };
type ComposerPredictionEvent = {
  schemaVersion: "1.0";
  sourceTurnId: string;
  contextVersion: string | null;
  suggestion: string | null;
  warnings: Array<{ type: "PREDICTION_UNAVAILABLE"; message: string }>;
  sourceMessageId: number;
};
```

`suggestion`은 최대 160 Unicode code point의 한 줄 또는 null이다. `warnings`는 항상
배열이다. 정상적으로 추천할 질문이 없거나 OFF/활동 gate에서 생략되면 null과 빈 배열을
받을 수 있다. 실패·한도·중복·stale 결과는 null과 `PREDICTION_UNAVAILABLE`를 반환한다.
warning 문구는 서버의 고정된 안전 문구이며 provider 원문을 전달하지 않는다. 기존 로컬
초안의 `reason` 필드는 제거했다. `usage`는 서버 기록·관리자 집계용이며 FE 결과에 없다.

## AI 내부 계약과 구분되는 부분

| 항목 | Spring이 받는 FE 요청/보내는 FE 응답 | 내부 AI #570 |
| --- | --- | --- |
| 요청 | `sourceMessageId`로 저장 메시지 선택 | `schemaVersion`, `sourceTurnId`, `contextVersion`, `learningActivity`, `messages` |
| 전송 | 인증된 POST SSE | 내부 토큰을 사용하는 POST JSON |
| 공통 응답 이름 | `schemaVersion`, `sourceTurnId`, `contextVersion`, `suggestion`, `warnings` | 동일한 이름 |
| 추가 선택자 | 응답에 `sourceMessageId`를 echo | 해당 필드 없음; strict 모델은 추가 필드를 거부 |
| 문맥 없음 | 사전 gate에서는 `contextVersion:null` 가능 | `contextVersion`은 비어 있지 않은 문자열 필수 |
| 비용 | FE 응답에 없음 | `usage`가 있으면 서버에서 보존 |

기존 학습 응답의 `turnId`는 AI 실행 UUID다. 현재 `ChatMessage`에는 그 UUID의 조회 연결이
저장되지 않는다. 이번 `sourceTurnId`는 Spring이 만든 `message-{저장메시지ID}` 상관키이며
AI가 이를 그대로 echo한다. FE는 기존 `turnId`를 `sourceMessageId`에 넣거나, 두 ID를
같다고 가정하지 않는다. FE는 요청/응답의 `sourceMessageId`를 대조하고 `sourceTurnId`는
서버의 상관키로 취급한다.

공통 필드명 채택이 두 HTTP 계약 전체의 동일함을 뜻하지 않는다. 실제 AI 모델로 검증할
때는 FE 전용 `sourceMessageId`를 제외한 공통 투영만 검증하며, 문맥 없는 gate 응답은
내부 AI 응답으로 검증되지 않는 것이 맞다.

## 취소·최신성·표시 규칙

요청마다 `AbortController`와 증가하는 client generation을 둔다. 타이핑/IME 시작,
새 turn, 세션·페이지·대화·source 변경, opt-out, unmount 때 이전 HTTP 요청을 abort한다.
추천 전용 cancel API는 없으며 학습 turn의 `/turns/cancel`을 추천 취소에 사용하지 않는다.
서버는 연결 종료를 감지하면 실제 HTTP future와 감시 작업을 취소한다. 이미 전달된 provider
작업/과금 중단은 보장하지 않으므로 abort 후 자동 재생성·재시도하지 않는다.

늦은 결과는 HTTP 취소와 별개로 버린다. 요청 당시 session/page/source와 client generation이
현재 값과 같고, 입력칸이 비어 있으며, IME 조합 중이 아니고, 추천 허용 상태인 경우에만
문자열을 표시한다. `schemaVersion`이 `1.0`인지, `sourceMessageId`가 요청한 값인지도
확인한다. `contextVersion`은 서버 소유 값이므로 FE가 계산하지 않는다. null이면
표시하지 않는다. 서버도 새 turn·페이지·reset·자격/자료 접근 변경을 재검사하지만 마지막
검사와 화면 변경의 경합은 FE generation/상태 검사로 차단해야 한다.

추천은 사용자의 명시적 선택으로 입력칸에 넣고 수정할 수 있게 한다. 자동 제출하지 않고,
HTML로 렌더링하지 않는다. null·warning·결과 없는 EOF·abort는 추천을 표시하지 않는
흐름으로 처리하며 저장된 학습 답변을 실패 상태로 바꾸지 않는다.

## HTTP 오류

`fetch`의 HTTP 상태와 응답 MIME을 먼저 확인한다. 비정상 상태는 기존 JSON 오류 envelope
(`error.code`)이며 SSE로 파싱하지 않는다. 다음은 대표 코드이며 기존 인증 흐름을 유지한다.

| 상태 | 의미/대표 코드 |
| --- | --- |
| 400 | 선택자 누락·0·음수·잘못된 JSON: `VALIDATION_FAILED`, `MALFORMED_REQUEST` |
| 401 | 로그인/JWT 문제: `AUTHENTICATION_REQUIRED`, `TOKEN_INVALID`, `TOKEN_EXPIRED` 등 |
| 403 | 이메일·연령·보호자/외부 AI 자격 또는 정지 계정의 기존 gate |
| 404 | `SESSION_NOT_FOUND`, `MATERIAL_NOT_FOUND`; 기능 OFF일 때 경로 자체가 없을 수도 있음 |
| 409 | 요청 도중 동의 세대 변경 등 기존 `GUARDIAN_CONSENT_CHANGED` |
| 429 | 기존 전체 AI quota의 `AI_QUOTA_EXCEEDED` |

추천별 중복/빈도/동시성/예산 거부는 200의 null+warning이며 별도의 재시도 신호가 아니다.
AI 내부 401/422/timeout/provider 오류는 FE의 내부 API 호출로 해결하지 않고, 서버가 선택적
추천 실패로 격리한다. 이미 SSE가 시작된 후 자격 철회·연결 오류가 나면 결과 없이 닫힐 수 있다.

## 활성화와 남은 확인

현재 Spring `EDUPILOT_COMPOSER_ENABLED=false`, `EDUPILOT_COMPOSER_POLICY_CONFIRMED=false`,
양의 사용자별 한도/비용 예약값 미설정(0)이다. AI의 `EDUPILOT_PREDICTION_ENABLED=false`도
독립 플래그다. 실제 설정·Compose 전달·배포·활성화는 이번 작업에서 하지 않았다. 기본 OFF
상태의 Swagger에서는 이 어댑터를 찾을 수 없으며, 위 문서와 로컬 테스트가 현재 연동 근거다.

FE의 실제 추천 UI·영속 opt-out API·모바일/키보드/스크린리더 동작은 이번 BE 변경에 포함되지
않았다. 기존 서버에는 명시적 TEACHBACK 단계가 없으므로 그 흐름을 QA 안에 숨겨 활성화하지
않고 별도 서버 단계 gate를 갖춰야 한다. 실제 FastAPI/provider·MySQL·FE·프록시 buffering/취소
E2E는 미검증이다. 로컬 H2·loopback와 실제 AI 모델 클래스 검증은 이 검증들과 구분한다.

코드 근거: [Spring controller](../main-service/src/main/java/io/edupilot/composer/ComposerPredictionController.java),
[Spring SSE/투영](../main-service/src/main/java/io/edupilot/composer/ComposerPredictionService.java),
[서버 문맥](../main-service/src/main/java/io/edupilot/composer/ComposerSnapshotService.java),
[AI 모델](../ai-service/src/edupilot_ai/models/composer_prediction.py),
[AI 모델 대조 도구](../tools/qa/verify-composer-ai-schema.py).
