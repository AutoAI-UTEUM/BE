# Issue #9 Worklog

기준 브랜치: `origin/develop` (`06bb679`)

## 작업 로그

- 2026-07-25 19:17 KST: 이슈 #9와 필수 계약/결정/테스트 문서 확인.
- 2026-07-25 19:18 KST: 최신 `origin/develop`에서
  `feature/9-ai-service-bootstrap` 생성.
- 2026-07-25 19:19 KST: Python 3.14.6과 uv 0.11.32 준비.
- 2026-07-25 19:21 KST: ai-service 부트스트랩 구현 시작.
- 2026-07-25 19:23 KST: Python 3.14.6에서 31개 패키지 의존성 해석 및
  `uv.lock` 생성, 설치 성공. 3.13 fallback 불필요.
- 2026-07-25 19:24 KST: uvicorn 팩토리 실행 후 health 200 및 인증된 turn
  고정 응답을 실제 HTTP로 확인.
- 2026-07-25 19:25 KST: pytest 7개, ruff, mypy 전체 통과.
- 2026-07-25 19:38 KST: 이슈 #10·#11 인수인계 문서와 500 INTERNAL 오류
  계약 테스트 추가.
- 2026-07-25 19:39 KST: pytest 8개, ruff, mypy 전체 재검증 통과.

## 이슈 #9 체크리스트 매핑

- [x] DEC-002 확인: Python 3.14.x, Grok 4.5, `AgentLlmProfile` 기준 반영.
- [x] FastAPI 프로젝트와 `uv` 의존성 관리 구조 정의.
- [x] 내부 API 표준 응답·오류 형식 구현.
- [x] `GET /health` 구현.
- [x] `X-Internal-Token` 검증 미들웨어 구현.
- [x] `POST /internal/ai/turn` v0.4 최소 구조 고정 응답 구현.
- [x] `LlmBridge` Protocol, 설정 주입, `FakeLlm` 테스트 더블 구현.
- [x] 가짜 값만 포함한 `.env.example`과 실행 문서 작성.
- [x] httpx ASGITransport 기반 계약 테스트 작성.
- [x] Python 3.14 의존성 잠금과 pytest/ruff/mypy 검증.
- [x] uvicorn 실행 후 health/turn 수동 smoke test.

## 검증 결과

- `uv sync --locked`: 성공 (CPython 3.14.6)
- `uv run pytest -q`: `8 passed`
- `uv run ruff check .`: 통과
- `uv run mypy`: 22개 소스 파일 검사 통과
- `uvicorn ... --factory`: `/health` 200, 인증된 `/internal/ai/turn` 200
- 테스트 중 실제 Grok 및 외부 네트워크 호출: 0회

## 문서 정합성

- `docs/api-spec.md` §8의 “서비스 간 인증 방식은 TBD” 문구와 달리, 더 높은
  우선순위의 `docs/ai-integration-contract.md` v0.4 §1 및 DEC-014는
  `X-Internal-Token`을 Accepted로 확정했습니다. 코드는 확정 계약을 따릅니다.
- 실제 Grok/xAI 호출 구현은 이슈 #9 범위에서 의도적으로 제외했습니다.
- health는 이 단계에서 프로세스 liveness만 나타냅니다. 외부 의존성 readiness는
  Epic 8 계약으로 미룹니다.

## 미결 질문

1. `MODEL_NAME`은 계약의 대체 경로에 따라 현재 `grok-4.5` alias를 사용합니다.
   운영 전 고정할 공식 dated slug를 어느 값으로 승인할지 확인이 필요합니다.
2. 계약은 timeout 값만 확정하고 환경 변수 이름은 확정하지 않았습니다. 현재 사용한
   `TURN_TIMEOUT_SECONDS`, `TURN_FIRST_EVENT_TIMEOUT_SECONDS`,
   `GRADE_TIMEOUT_SECONDS`, `QUIZ_ASSESSMENT_TIMEOUT_SECONDS`,
   `DIAGNOSIS_TIMEOUT_SECONDS`, `EXTRACT_TIMEOUT_SECONDS`를 배포 계약으로
   승인할지 확인이 필요합니다.
3. v0.4는 오류 `category`를 확정했지만 인증·요청 검증·일반 내부 오류의 세부
   `code` 목록은 확정하지 않았습니다. 부트스트랩에서 사용한
   `AI_INTERNAL_AUTH_FAILED`, `AI_REQUEST_INVALID`, `AI_INTERNAL_ERROR`를
   후속 계약에서 승인하거나 교체해야 합니다.

## 이월 메모

- BaseHTTPMiddleware는 SSE 스트리밍과 궁합 이슈 알려짐 — #25 구현 시 pure ASGI 미들웨어 전환 검토
- 응답 model 필드 대조 assertion은 LlmBridge 실구현이 없어 #23으로 이월

---

# Phase 3 — Issue #25 스트리밍 사전 조사

기준 브랜치: `origin/develop` (`ddaecd9`)

## 작업 로그

- 2026-07-26 15:59 KST: Phase 1·2와 변경을 섞지 않기 위해 최신 develop에서
  문서 전용 `feature/25-streaming-research` 브랜치 생성.
- 2026-07-26 16:00 KST: 통합 계약 v0.4 §5, 현재
  `InternalTokenMiddleware`, Starlette와 xAI 공식 문서를 대조. 코드 변경 없이
  #25 구현 경계와 테스트 항목만 정리.

## BaseHTTPMiddleware → pure ASGI 전환 범위

현재 전환 대상은 `core/middleware.py`의 `InternalTokenMiddleware` 한 곳이다.
Starlette 공식 문서는 `BaseHTTPMiddleware`의 `contextvars` 전파 제한을 명시하고
pure ASGI middleware가 이를 피한다고 안내한다.

- `__call__(scope, receive, send)` 형태로 바꾸고 `http`가 아닌 lifespan·websocket
  scope는 즉시 하위 app으로 전달한다.
- 요청별 trace ID는 공유 인스턴스 필드가 아니라 `scope["state"]["trace_id"]`에
  둔다. Spring의 `X-Trace-Id`가 있으면 유지하고, 없으면 새로 생성한다.
- `/internal/` 경로만 `X-Internal-Token`을 constant-time 비교한다. 인증 실패
  envelope는 기존 `build_error_response`를 ASGI response callable로 실행해
  body를 버퍼링하지 않는다.
- `send`를 감싸 `http.response.start`에 `X-Trace-Id`를 추가한다. 이후
  `http.response.body`의 `more_body=True` 청크는 검사·합치기·재직렬화하지 않고
  그대로 전달한다.
- downstream disconnect 또는 task cancellation은 삼키지 않고 상류 xAI stream
  context가 `finally`에서 닫히도록 전파한다.
- 회귀 테스트는 health·인증 오류·trace 전파 외에 첫 SSE 청크 즉시 전달,
  다중 body 청크 보존, client disconnect 시 upstream close를 포함한다.

근거:
[Starlette middleware 공식 문서](https://www.starlette.io/middleware/)

## Grok SSE 파싱 메모

xAI Chat Completions streaming은 요청에 `"stream": true`를 보내고 SSE의
`data:` 라인으로 `chat.completion.chunk` JSON을 전달한 뒤
`data: [DONE]`으로 끝난다. reasoning 모델은 긴 timeout이 필요할 수 있다.

- `httpx.AsyncClient.stream("POST", ...)`와 `response.aiter_lines()`를 사용한다.
  빈 줄과 `:` comment는 무시하고 `data:` 필드만 파싱한다.
- `[DONE]` 전까지 각 JSON의 `object`, `model`, `choices[0].delta`를 검증한다.
  알려지지 않은 필드는 무시하되 원시 payload·프롬프트·학생 답안은 로그에
  남기지 않는다.
- 일반 text stream은 `delta.content`를 순서대로 누적하고 같은 값을
  `content_delta`로 전달한다. 마지막 model을 설정 model과 대조하고 usage는
  provider가 보낸 마지막 유효 값을 사용한다.
- structured output stream의 청크는 완성된 객체가 아니라 점진적으로 만들어지는
  JSON 문자열이다. 전체 문자열을 누적해 종료 후 Pydantic으로 검증하기 전에는
  부분 JSON을 신뢰하지 않는다.
- `thought_summary`에는 provider의 내부 추론 원문을 넣지 않는다. 계약이 허용한
  짧은 진행 단계나 별도 검증된 요약만 전달한다.
- malformed JSON, 조기 EOF, `[DONE]` 없는 정상 종료, 최종 schema 불일치는
  SCHEMA 오류 후보이고, read/first-event timeout은 TIMEOUT으로 분류한다.
- 취소 시 stream response를 즉시 닫고 생성 task를 취소한다. 부분 content는
  Spring이 저장하지 않는다는 계약을 유지한다.

근거:
[xAI streaming 공식 문서](https://docs.x.ai/developers/model-capabilities/text/streaming),
[xAI structured outputs 공식 문서](https://docs.x.ai/developers/model-capabilities/text/structured-outputs)

## turn 스트리밍 경로 설계 메모

1. 인증과 요청 DTO 검증을 마친 뒤 `status(stage=PLANNING)`을 보낸다.
2. ContextBuilder, Orchestrator, PolicyVerifier는 우선 비스트리밍으로 실행한다.
3. Explainer·QA 도구만 streaming LlmBridge 경로를 사용한다. 그 외 도구는
   계약대로 비스트리밍을 유지한다.
4. dispatcher는 provider delta를 `content_delta`로 변환하며 동시에 완성 본문을
   누적한다. `thought_summary`와 `ui_action`은 별도 안전 DTO 검증 후 보낸다.
5. 모든 action과 최종 schema/statePatch 검증이 성공하면 누적 delta와 동일한
   messages content를 가진 `completed`를 정확히 한 번, 마지막 이벤트로 보낸다.
6. 실패하면 `completed` 없이 표준 category를 가진 `error` 하나로 종료한다.
   error 이후 추가 event를 보내지 않는다.
7. provider 출력과 무관하게 10초마다 SSE comment heartbeat를 보낸다. heartbeat는
   계약 event 수에 포함하지 않는다.
8. Spring 연결 종료가 감지되면 FastAPI 생성 task와 xAI stream을 취소한다.
   서버측 replay buffer와 `Last-Event-ID`는 MVP 범위에서 만들지 않는다.

필수 불변식 테스트:

- `content_delta` 전체 누적값과 `completed.result.messages[].content`가 동일하다.
- `completed`는 정확히 한 번 마지막에 오며 `error`와 상호 배타다.
- 첫 이벤트 30초, 전체 turn 180초, heartbeat 10초가 독립적으로 적용된다.
- malformed upstream event, provider timeout, model mismatch, downstream disconnect
  경로에서 외부 네트워크 없이 respx/fake stream 테스트가 통과한다.
- raw chain-of-thought, 프롬프트, 학생 답안 원문이 event와 로그에 노출되지 않는다.

## #27 협의 질문과 이월

1. 내부 `/internal/ai/turn`이 항상 SSE를 반환할지, `Accept` negotiation으로 현재
   JSON 응답과 병행할지 v0.4에 wire-level 확정이 없다. #27 승인 없이 endpoint
   동작을 추측 구현하지 않는다.
2. AgentOutput structured JSON을 그대로 stream하면 Markdown이 부분 JSON 안에
   섞인다. agent 본문은 plain text stream으로 분리할지, 증분 JSON parser를
   둘지 #27에서 결정해야 한다.
3. `thought_summary` 생성 주체와 schema, SSE의 `id`·`event` 필드 사용 여부를
   #27에서 확정해야 한다.
4. 이 브랜치는 #25 구현이 아니라 사전 조사만 포함하므로 PR 병합이 #25를
   자동 종료하지 않도록 `Closes #25` 대신 `Refs #25`를 사용한다.

## Phase 3 결과

- [x] Phase 1·2 오류·경계 테스트 보강 결과 확인.
- [x] pure ASGI 전환 범위 정리.
- [x] Grok SSE 파싱과 취소·오류 처리 방식 정리.
- [x] turn 스트림 event 순서·불변식·테스트 설계 정리.
- [x] 코드·의존성 변경 없음.
