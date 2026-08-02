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

- BaseHTTPMiddleware는 SSE 스트리밍과 궁합 이슈 알려짐 — 2026-07-27 #25에서
  pure ASGI 미들웨어로 전환 완료
- 응답 model 필드 대조 assertion은 LlmBridge 실구현이 없어 #23으로 이월

---

# Issue #5 Worklog

기준 브랜치: `origin/develop` (`4bf27d7`, #64 병합분으로 rebase)

## 작업 로그

- 2026-07-26 02:23 KST: Phase 0 수행. 최신 `origin/develop`에서 이슈 #9
  부트스트랩의 `src/edupilot_ai/factory.py` 존재를 확인하고
  `feature/5-pdf-extract` 생성.
- 2026-07-26 02:24 KST: 통합 계약 v0.4 §1·§4·§6.1, api-spec §8,
  error-code, DEC-006·016, 테스트 전략, 이슈 #5 체크리스트 확인.
- 2026-07-26 02:25 KST: 기존 조사 결론이 없어 지시대로 `pypdf` 채택.
  페이지 순서·암호화 여부·텍스트 레이어를 LLM과 외부 네트워크 없이 판정할 수
  있고 Python 3.14.6에서 `pypdf 6.14.2` 설치와 import가 성공함.
- 2026-07-26 02:26 KST: 청크 업로드 제한, 임시 파일 정리, 추출 코어,
  `/internal/ai/extract`와 생성형 PDF fixture 계약 테스트 구현.
- 2026-07-26 02:27 KST: uvicorn 실제 실행 후 2페이지 PDF를 multipart
  전송해 HTTP 200과 계약 응답을 확인. 처리 뒤 임시 파일이 남지 않음을 확인.
- 2026-07-26 02:28 KST: 46,175,332바이트(약 44MiB), 300페이지 합성 PDF로
  실제 HTTP 업로드+추출 실측. HTTP 200, `pageCount=300`, 총 0.155953초.
- 2026-07-26 16:02 KST: Phase 3 경계값 보강으로 정확히 300페이지, 설정된
  바이트 상한과 동일한 파일 허용 및 잘못된 Content-Type 거부 테스트 추가.

## 이슈 #5 체크리스트 매핑

- [x] 멀티파트 수신, `.pdf` 확장자·`application/pdf` Content-Type·`%PDF-`
  매직 바이트·45MiB 제한 검증.
- [x] 페이지별 텍스트 추출·정제, 1-based `pageNumber`, 공백 페이지 `text:""`,
  `pageCount` 응답.
- [x] 손상·암호화·전 페이지 공백(스캔본)을 `EXTRACTION_FAILED`로 분류하고
  301페이지를 `PAGE_LIMIT_EXCEEDED`로 거부.
- [x] 임시 파일을 성공·실패 모두 `finally`에서 삭제하고 영속 저장소를 두지 않음.
- [x] 테스트 코드에서 정상·공백·암호화·301페이지·손상·비PDF fixture 생성.
- [x] ASGITransport 계약 테스트와 순수 추출 단위 테스트 작성.
- [x] HANDOFF에 실제 extract curl 응답과 Spring timeout·재시도 지침 추가.
- [x] 상한 근접 처리 시간 측정과 timeout 검토.

## 처리 시간과 timeout 판단

- 로컬 환경: macOS, CPython 3.14.6, pypdf 6.14.2, uvicorn loopback.
- fixture: 46,175,332바이트, 300페이지. 1페이지에 짧은 텍스트가 있고 나머지
  페이지는 공백이며, 파일 크기는 PDF attachment로 상한에 가깝게 구성.
- 결과: 업로드 46,175,540바이트(multipart 포함), 응답 8,655바이트,
  총 0.155953초, HTTP 200.
- 판단: 합성 fixture는 복잡한 폰트·콘텐츠 스트림을 가진 실제 강의 PDF보다
  훨씬 단순하므로 timeout을 낮출 근거로 사용하지 않는다. 계약의 120초를 유지한다.
  추출 오류는 모두 `retryable=false`라 동일 파일 자동 재시도는 하지 않는다.

## 검증 결과

- `uv sync --locked`: 성공 (CPython 3.14.6, pypdf 6.14.2)
- `uv run pytest -q`: 26개 통과
- `uv run ruff check .`: 통과
- `uv run mypy`: 29개 소스 파일 검사 통과
- 실제 Grok/외부 AI 호출: 0회

## 미결 질문

1. 사용자 요청의 전체 마감이 `<YYYY-MM-DD HH:00>` 자리표시자로 남아 있어 절대
   마감과 “40분 전 구현 중단” 시각을 계산할 수 없다. 이번 작업은 Phase별 예산을
   상한으로 사용했다.
2. v0.4는 `EXTRACTION_FAILED`의 “하위 사유 코드 분류”를 요구하지만 표준 오류
   envelope에는 하위 사유 필드가 없다. 이번 구현은 안정된 상위 code를 유지하고
   내부 `PdfFailureReason`과 안전한 message로 손상/암호화/텍스트 없음만 구분했다.
   다음 계약에서 `reasonCode` 같은 기계 판독 필드를 추가할지 #27 협의가 필요하다.
3. `PAGE_LIMIT_EXCEEDED`와 `FILE_TOO_LARGE`의 category가 v0.4에 명시되지 않았다.
   재시도해도 바뀌지 않는 요청 제약이므로 현재 `SCHEMA`, `retryable=false`로
   분류했다. Spring 매핑 전에 승인 필요.

---

# Issue #23 Worklog

기준 브랜치: `origin/develop` (`ddaecd9`)

## 작업 로그

- 2026-07-26 02:30 KST: Phase 1 커밋이 포함되지 않은 최신 develop에서
  `feature/23-turn-core` 생성.
- 2026-07-26 02:31 KST: 통합 계약 v0.4 §3·§5·§7,
  agent-system-spec §2~§7, DEC-002, 이슈 #23·#27 상태 확인. #27의 계약
  체크리스트가 전부 미승인임을 확인.
- 2026-07-26 02:32 KST: xAI 공식 structured outputs·reasoning·chat
  completions 문서를 확인하고 provider 어댑터 경계 구현 시작.
- 2026-07-26 12:54 KST: `date` 기준 Phase 2 시작 후 4시간 예산 초과를 확인.
  절반만 연결된 ContextBuilder/Orchestrator/Agent 코드는 제거하고 새 기능 구현 중단.
- 2026-07-26 12:56 KST: 독립적으로 완료 가능한 xAI 어댑터·프로필·respx
  테스트만 정리. pytest/ruff/mypy 통과.
- 2026-07-26 15:50 KST: 사용자가 전체 마감을 18:00 KST로 연장해 Phase 2
  작업을 재개. 17:20부터 새 구현을 중단하는 기준을 적용.
- 2026-07-26 15:54 KST: ContextBuilder, Plan 생성, 정책 검증, 순차 dispatcher,
  설명·QA 에이전트와 turn 응답 조립을 완료.
- 2026-07-26 15:56 KST: FakeLlm 계약 테스트를 포함해 pytest 24개,
  ruff, mypy 전체 통과.
- 2026-07-26 16:04 KST: Phase 3 오류·경계 보강으로 provider usage 합산,
  agent timeout, intervention budget, event payload 불일치, statePatch 충돌
  테스트를 추가.

## 이슈 #23 체크리스트 매핑

- [x] `LlmBridge`가 validated output과 provider `model`·usage
  (`inputTokens`, `outputTokens`, `reasoningTokens`)를 함께 반환하도록 확장.
- [x] `https://api.x.ai/v1/chat/completions` OpenAI-compatible 어댑터 구현.
- [x] 모든 요청에 `response_format.type=json_schema`, `strict=true`,
  Pydantic JSON Schema, reasoning effort, max tokens, timeout 적용.
- [x] timeout→`TIMEOUT`, structured output 검증 실패→`SCHEMA`,
  provider/network 오류→`INTERNAL` 분류.
- [x] 실제 응답 `model`과 설정 model 불일치 warning assertion 구현.
- [x] Orchestrator/Explainer/QA effort 프로필 설정을 app Settings에 반영.
- [x] app lifespan이 production HTTP client와 bridge를 소유하고 종료 시 close.
- [x] respx로 Authorization·와이어 JSON·usage·model mismatch·timeout·schema·503
  경로 검증. 실제 xAI/외부 네트워크 0회.
- [x] ContextBuilder가 Spring 스냅샷을 불변 AgentContext로 변환.
- [x] Orchestrator가 strict TurnPlan structured output을 생성하고 SCHEMA 실패 시
  정확히 1회 재생성.
- [x] PolicyVerifier가 허용 도구, pipeline 전용 도구, event/argument 일치,
  qaThreadMode와 intervention budget을 검증.
- [x] ToolDispatcher가 도구를 순차 실행하고 action별 성공·실패를 기록하며
  statePatch를 충돌 없이 병합.
- [x] ExplainerAgent가 현재 페이지를 주 근거로 detailLevel,
  learnerMemoryDigest를 반영.
- [x] QaAgent가 START_NEW/FOLLOW_UP, qaThreadDigest, latestRepair를 반영하고
  근거 부족을 명시.
- [x] QUIZ_TYPE_SELECTED와 DIAGNOSIS_ANSWER_SUBMITTED는 각각 #31, #38
  실행 스텁으로 유지.
- [x] turn 응답에 turnGoal, actionsExecuted, messages, 허용목록 statePatch,
  uiActions, memoryCandidates와 reasoningTokens 포함 usage를 조립.
- [x] FakeLlm 기반 explain, QA 새 질문·후속 질문, Plan 거부, SCHEMA 재생성,
  statePatch 위반과 부분 실패 계약 테스트 작성.

## 검증 결과

- `uv sync --locked`: 성공 (CPython 3.14.6, respx 0.23.1)
- `uv run pytest -q`: 52개 통과
- `uv run ruff check .`: 통과
- `uv run mypy src tests`: 40개 소스 파일 검사 통과
- 테스트 중 실제 Grok 및 외부 네트워크 호출: 0회

## 완료 범위와 이월

- 설명과 QA turn core는 완료했습니다.
- QUIZ_TYPE_SELECTED의 실제 생성은 #31, DIAGNOSIS_ANSWER_SUBMITTED의 실제
  repair는 #38로 이월했습니다.
- 스트림 이벤트 전달은 #25 범위로 이월했습니다.
- #27 서면 확정을 코드와 계약 문서에 반영한 뒤 PR #65를 Ready for review로
  전환합니다.

## 2026-07-26 당시 미결 질문

1. #27이 미승인인 상태에서 qaThread의 `threadRef` 생성 주체·형식과
   `statePatch` 세부 JSON Schema가 확정되지 않았다. → 2026-07-27 해소.
2. 이슈 #23은 LlmBridge “재시도”를 요구하지만 v0.4는 Spring이
   `retryable=true`만 최대 1회 재시도하고 SCHEMA는 Orchestrator 내부 1회
   재생성으로 소진한다고 규정한다. provider adapter 자체 HTTP 재시도를 둘지
   #27에서 명확히 해야 한다. 이번 어댑터는 중복 비용을 피하려고 분류만 하고
   자동 재시도하지 않는다. → 2026-07-27 자동 재시도 없음으로 해소.
3. Plan action `args`, `statePatch.activeQuizId`, `pendingDiagnosis`의 세부
   schema는 #27 승인 전까지 v0.4의 허용 키와 event별 검증만 적용했다.

## #27 확정 반영 (2026-07-27)

- `origin/develop`의 #64 병합분 위로 `feature/23-turn-core`를 rebase하고,
  `uv.lock`을 재생성해 PDF extract와 turn 의존성을 모두 보존했습니다.
- Policy 보정 범위를 `page`·`detailLevel`로 제한하고 보정 결과를
  `actionsExecuted[].adjustments`에 기록했습니다. 여분 args 키는 조용히
  제거하며 이벤트-도구 불일치, 파이프라인 도구, FOLLOW_UP 문맥 부재,
  `threadRef` 위조는 계속 거부합니다.
- QA `threadRef`는 Spring 소유로 확정했습니다. `START_NEW` patch는
  `{mode}`만 반환하고, `FOLLOW_UP`은 스냅샷의 `threadRef`를 그대로
  반환합니다.
- AI `uiActions`는 항상 빈 배열이고 Spring이 W1~W7 규칙으로 마지막 상태
  전이의 위젯 1개만 생성하도록 계약 문서에 반영했습니다.
- QA 근거 부족·퀴즈 스텁·교정 스텁의 사용자 노출 문구가 제공된
  프롬프트 자산 §5의 한국어 문구와 일치함을 다시 확인했습니다.
- 검증: `uv run pytest -q` 52개, `uv run ruff check .`,
  `uv run mypy src tests` 전체 통과. 테스트 중 실제 Grok/외부 네트워크 호출
  0회.

## #25·후속 이월

- 현재 xAI 어댑터는 각 LLM 호출에 turn 전체 설정값 180초를 적용하므로
  Plan+Agent 2회 호출의 합산 시간이 이론상 180초를 넘을 수 있습니다. #25에서
  turn 시작 시각을 기준으로 남은 시간 예산을 각 호출에 전달해야 합니다.
  → 2026-07-27 `TurnDeadline`으로 해소.
- Orchestrator 입력에 현재 AgentContext 전체가 직렬화됩니다. Plan에 필요한
  요약 필드만 전달하는 토큰 비용 최적화는 별도 후속 작업입니다.
- 현재 프롬프트는 동작 검증용 최소 골격입니다. 제공된 한국어 프롬프트 자산의
  도입 인사 금지, LaTeX, 수준별 전략, 프롬프트 인젝션 방어 이식은 별도 이슈로
  분리합니다.
- `statePatch.activeQuizId`와 `pendingDiagnosis`의 상세 JSON Schema는 각각
  #31·#38 실제 구현 전에 계약 문서에 먼저 확정해야 합니다.

---

# Issue #25 Worklog

기준 브랜치: `origin/develop` (`3ed1c3e`, PR #65 병합 포함)

## 작업 로그

- 2026-07-27 14:38 KST: 최신 develop에서
  `feature/25-turn-streaming` 생성. `orchestration/`과 #25 timeout 이월을
  확인했습니다.
- 2026-07-28 05:28 KST: 작업 중 갱신된 `origin/develop`(`ac09367`)을
  비파괴 merge로 반영한 뒤 pytest 64개, ruff, mypy를 다시 통과했습니다.
- 사용자 요청의 마감이 `<YYYY-MM-DD HH:00>` 자리표시자로 남아 있어
  절대 마감·30분 전 구현 중단 시각은 계산하지 못했습니다.
- xAI 공식 Streaming 문서의 Chat Completions `stream=true`,
  `data: {...}`·`data: [DONE]` framing과 공식 Cost Tracking 문서의
  `stream_options.include_usage=true` 최종 usage 방식을 기준으로 adapter를
  구현했습니다.
- provider-neutral `complete_text_stream`과 xAI SSE parser를 추가하고,
  streaming 요청에서는 `response_format`과 모델 `thoughtSummary`를
  제거했습니다.
- turn 시작 시각의 단일 deadline에서 Plan·SCHEMA 재생성·Agent 호출마다
  남은 timeout을 계산하도록 변경했습니다. 남은 시간이 0 이하이면 provider를
  호출하지 않고 `TIMEOUT`으로 종료합니다.
- 내부 토큰/trace middleware를 `BaseHTTPMiddleware`에서 pure ASGI로
  전환해 streaming body를 버퍼링하지 않도록 했습니다.
- `Accept: application/x-ndjson`일 때만 NDJSON을 반환하고, Accept 미지정은
  기존 JSON 응답을 유지했습니다.
- 10초 무이벤트 heartbeat와 30초 첫 이벤트 상한을 공통 stream wrapper에
  반영했습니다.
- 2026-07-27 17:12 KST: FakeLlm 주입 uvicorn(`127.0.0.1:8025`)에서 실제
  curl 실행. `PLANNING → ANSWERING → content_delta 2개 → FINALIZING →
  completed` 순서와 delta 누적/완료 메시지 일치를 확인했습니다. 실제
  Grok/xAI 및 외부 네트워크 호출은 0회입니다.

## 이슈 #25 체크리스트 매핑

- [x] Grok Chat Completions SSE 수신·frame 파싱·`[DONE]`·최종 usage 처리.
- [x] 설명·QA turn의 표준 NDJSON 이벤트 발행.
- [x] 모델 원시 추론 대신 stage 기반 결정적 한국어 `thought_summary` 생성.
- [x] stream timeout·provider/SCHEMA/INTERNAL 오류를 terminal `error`로
  변환하고 completed와 상호 배타 보장.
- [x] `content_delta` 누적과 `completed.result.messages[].content` 일치 검증.
- [x] `TurnDeadline`으로 Plan+Agent 전체 180초 예산 분배.
- [x] 10초 heartbeat와 첫 이벤트 30초 상한.
- [x] pure ASGI 내부 토큰·trace middleware 전환 및 회귀 검증.
- [x] Accept 미지정 기존 JSON 경로 유지.
- [x] respx SSE wire test와 FakeLlm 정상 설명/QA·중단·예산 소진 golden test.
- [x] 통합 계약 §5와 #26 HANDOFF 갱신.

## 검증 결과

- `uv sync --locked`: 성공 (CPython 3.14.6)
- `uv run pytest -q`: 64개 통과
- `uv run ruff check .`: 통과
- `uv run mypy src tests`: 43개 소스 파일 검사 통과
- 기존 비스트리밍 테스트: 전부 통과
- 실제 Grok/xAI 및 테스트 외부 네트워크 호출: 0회

## 완료 범위와 이월

- AI Service 내부 NDJSON과 xAI SSE 변환은 완료했습니다.
- Spring의 NDJSON 소비·외부 SSE 변환·completed 후 1회 저장은 이슈 #26
  범위입니다.
- FE 연결 종료가 Spring을 거쳐 FastAPI와 provider까지 취소되는 end-to-end
  검증은 #26 통합 테스트에서 수행해야 합니다. AI Service generator와
  `httpx.AsyncClient.stream`은 취소 시 context를 닫도록 구성했습니다.
- 퀴즈·교정 스텁은 provider 본문 stream 없이 terminal 이벤트만 반환합니다.
- 실제 xAI live 테스트는 절대 규칙에 따라 수행하지 않았습니다.

## GitHub 게시 대기

- 2026-07-28 확인 결과 로컬 환경에 `gh`가 없어 이슈 #25 체크리스트 갱신과
  develop 대상 PR 생성은 대기합니다. 완료 항목은 위 체크리스트 매핑과 같으며,
  `gh` 설치·인증 후 GitHub 이슈 본문에 완료 항목만 반영해야 합니다.

### PR 본문 초안

```markdown
## 변경 요약

- `Accept: application/x-ndjson` 요청에 내부 turn NDJSON 스트림을 제공합니다.
- Explainer·QA의 xAI SSE를 `content_delta`로 변환하고, Plan의 기존 structured
  output 경로와 Accept 미지정 JSON 응답은 유지합니다.
- turn 단일 deadline, 첫 이벤트 제한, 10초 heartbeat, terminal 이벤트 불변식을
  적용했습니다.
- 내부 토큰·trace 미들웨어를 pure ASGI 방식으로 전환했습니다.
- 통합 계약 §5와 Spring #26용 HANDOFF를 실제 FakeLlm curl 결과에 맞춰
  갱신했습니다.

## 검증

- `uv run pytest -q` — 64 passed
- `uv run ruff check .` — passed
- `uv run mypy src tests` — passed
- FakeLlm uvicorn + NDJSON curl 수동 확인
- 실제 Grok/xAI 및 테스트 외부 네트워크 호출 0회

Closes #25
```

---

# ReportAgent·ReportQuery 계약 (#121)

기준 브랜치: `origin/develop` (`e221a1f`, 2026-08-02)

## 구현 범위

- `POST /internal/ai/reports/generate`, `POST /internal/ai/reports/query`의
  strict Pydantic 요청·LLM 출력·응답 모델을 추가했습니다.
- 생성 요청의 criterion/evidence 중복, eligibility 참조, source 집합 정합성을
  입력 경계에서 검증합니다.
- LLM 출력의 criterion/evidence/score/eligibility/반복 오개념 불변식을 요청
  snapshot과 대조하는 순수 validator를 추가했습니다.
- 구조적으로 유효하지만 계약 불변식을 위반한 출력은 사유 코드만 전달해 1회
  재생성하고, 다시 실패하면 `AI_RESPONSE_INVALID`/`SCHEMA` 502로 종료합니다.
- ReportAgent는 high/180초, ReportQuery는 medium/60초 전용 profile을 사용합니다.
- 리포트 본문·질문·evidence fact는 로그에 남기지 않고 reportId,
  generationId, criterion/evidence 개수와 검증 사유 코드만 기록합니다.
- Spring DTO 대조용 JSON Schema를
  `docs/contracts/report-agent.schema.json`에 생성했습니다.
- 기존 grading/support 오류 helper 공통화는 선택 범위이며, 기존 동작 변경을
  피하기 위해 reporting 지역 helper로 제한했습니다.

## 검증 결과

- `uv run pytest -q`: 141개 통과.
- `uv run ruff check .`: 통과. 커밋 금지 미추적 `smoke_live.py`는 내용과
  해시를 보존한 채 검사 동안에만 작업 경로 밖으로 이동했습니다.
- `uv run mypy src tests`: 64개 소스 파일 검사 통과.
- FakeLlm만 사용했고 실제 Grok/xAI 및 외부 네트워크 호출은 0회입니다.

## 미결 5건 — Spring 계약 회신 대기

1. `reportId`, `generationId`, `evidenceId`, criterion key의 형식과 최대 길이.
2. `scope.periodStart/periodEnd`, evidence `occurredAt`의 날짜 형식과 기간 역전
   검증 책임.
3. `criterionEligibility`가 모든 요청 criterion을 정확히 한 번씩 포함해야
   하는지와 criterion의 `allowedSourceTypes` 대조 책임.
4. `previousReport`에 허용할 공개 요약 범위와 criterion 결과 외 필드
   (`#121 §7-4`).
5. `trend`는 Spring 결정값을 AI 요청에만 제공할지, 응답에도 반영할지
   (`#121 §7-5`). 현재 AI 출력에는 포함하지 않습니다.

## 남은 작업·이월

- Spring #118 내부 DTO 승인과 contract fixture 대조 후 Draft를 해제합니다.
- 최대 evidence 수·fact 길이·token budget은 #118 승인값에 맞춰 제한합니다.
- 문체·과잉 일반화 방지 프롬프트 강화는 #124, 리포트 QA golden·보안 테스트는
  #122 범위입니다.

---

# Issue #44 구조화 로깅 (2026-07-29 develop 정렬)

기준 브랜치: `origin/develop` (`95e2a7e`)

## 구현 결과

- [x] `X-Trace-Id`를 요청 contextvars에 바인딩하고 Spring이 전달한 값을
  응답 헤더와 AI Service 로그에 그대로 사용하도록 연결.
- [x] 승인된 필드만 직렬화하는 한 줄 JSON formatter 구현:
  `timestamp`, `level`, `service`, `environment`, `traceId`, `message`와
  선택 필드 `turnId`, `actionId`, `sessionId`, `endpoint`, `agent`, `tool`,
  `status`, `durationMs`, `errorCode`, `model`, `attempt`, `failureKind`.
- [x] pure ASGI 미들웨어를 유지하면서 JSON·NDJSON 요청 종료 시점까지
  traceId·상태·처리 시간을 기록.
- [x] 최신 설명·QA·Quiz·Repair·메모리 dispatch 경로에 action 성공/실패
  로깅을 적용하고, xAI structured·stream 호출에 모델·처리 시간·실패 분류·
  시도 횟수만 기록.
- [x] #86 PolicyViolation WARN은 한 번만 유지하고 action args 값 대신
  도구명과 인자 키만 기록해 학생 답안·메모리 원문 노출을 차단.
- [x] `ENVIRONMENT=local|dev|prod` 설정 추가. local은 DEBUG,
  dev/prod는 INFO.

## 검증

- `uv run pytest -q`: 117개 통과.
- `uv run ruff check .`: 통과.
- `uv run mypy src tests`: 58개 소스 파일 검사 통과.
- respx/FakeLlm만 사용했고 실제 Grok·외부 네트워크 호출은 0회.
- 프롬프트·응답·provider 오류 body·PDF·학생 답안 표식이 로그에 포함되지
  않음을 테스트.

## 미결 사항

- uvicorn 자체 access/startup 로그는 uvicorn 소유의 콘솔 형식입니다.
  애플리케이션 로그는 JSON으로 고정했으며, uvicorn 로그까지 JSON으로
  통합할지는 후속 배포 설정에서 결정해야 합니다.

---

# Issue #31 Worklog — QuizAgent·GraderAgent

기준 브랜치: `origin/develop` (`20a5896`, #25·#83·#84 반영)

## 작업 결과

- 2026-07-28 16:35 KST: `feature/31-quiz-grader`에서 QuizAgent·GraderAgent
  초안 구현.
- 2026-07-28 19:03 KST: 최신 develop에 rebase하고 #25의 턴 deadline·스트림
  인터페이스와 #83의 `learnerConfidence` enum 계약을 통합.
- QuizAgent가 `GENERATE_QUIZ_MCQ|OX|SHORT|ESSAY` 스텁을 대체하며,
  제공된 페이지 텍스트, 학습자 수준·confidence·memory digest를 반영하는
  structured output을 생성합니다.
- 퀴즈 생성 프로필은 `reasoning_effort=medium`, GraderAgent는 `high`로
  분리했습니다.
- turn 응답에 선택적 내부 `quiz`를 추가하고 `activeQuizId` patch는 생성하지
  않습니다. 영속 ID 발급과 공개/비공개 필드 분리는 Spring 책임입니다.
- `/internal/ai/grade`는 SHORT/ESSAY만 허용하고 questionId 집합이 다르면
  LLM 호출 전 HTTP 400 `AI_REQUEST_INVALID`로 거부합니다.
- 루브릭 항목별 `scoreRatio`를 코드에서 가중 합산하며, LLM이 함께 반환한
  score·verdict와 불일치하면 정확히 1회 재생성 후 `SCHEMA`로 종료합니다.
- `docs/prompt-assets.md`는 develop에 없어, 사용자가 제공한 프롬프트 자산
  §4의 채점 규율과 `agent-system-spec` §4.4~4.5만 적용했습니다.

## #30 확정(2026-07-28 서면 승인)

### 퀴즈 생성 스키마

- 공통: `generationId`, `quizType`, `coverage{startPage,endPage}`, `title`,
  `questionCount(5~10)`, `questions[]`.
- 문항 공통 공개 필드: `questionId`, `questionText`, `points`.
- MCQ: 공개 `choices[]{choiceId,text}` / 비공개 `answerChoiceId`,
  `explanation`.
- OX: 비공개 `answerValue`, `explanation`.
- SHORT: 비공개 `referenceAnswer`, `gradingCriteria`.
- ESSAY: 비공개 `modelAnswer`, `rubric[]{criterion,weight}`이며 weight 합은 1.
- 정답·해설·기준 답안·채점 기준·루브릭은 학생 비공개 필드입니다. Spring이
  내부 전체 JSON을 분리 저장하고 외부 DTO에서 제거합니다.

### 채점

- 요청·응답은 계약 §6.2 필드를 유지합니다.
- LLM 전용 draft item은 `questionId`, `rubricScores[]{criterion,scoreRatio}`,
  `score`, `verdict`, `feedback`입니다. API 응답에는 rubricScores를 노출하지
  않고 코드가 확정한 item score·verdict만 반환합니다.
- verdict 경계는 만점 비율 `CORRECT >= 0.8`, `WRONG <= 0.2`, 그 사이는
  `PARTIAL`입니다.

### A1~A3 확정 결정

1. A1: AI Service가 내부 turn의 `quiz` 전체 JSON을 반환하고 Spring이
   공개/비공개 필드를 분리 저장합니다. `activeQuizId`는 Spring이 발급하며
   AI의 statePatch에는 설정하지 않습니다.
2. A2: MVP coverage는 turn snapshot에 실제 제공된 페이지만 허용합니다.
   누적 범위 퀴즈는 후속 이슈에서 별도 설계합니다.
3. A3: `generationId`는 AI가 생성하는 추적용 ID입니다. 멱등성의 원천은
   Spring의 `requestId`이며 generationId를 멱등 키로 사용하지 않습니다.

## 이슈 #31 체크리스트 매핑

- [x] 유형별 QuizAgent structured output과 5~10개 문항 불변식.
- [x] `GENERATE_QUIZ_*` turn 도구 연결.
- [x] `/internal/ai/grade`와 questionId 기반 매칭.
- [x] 루브릭 가중 합산·점수 범위·verdict 코드 검증.
- [x] schema 불일치 1회 재생성.
- [x] FakeLlm 기반 계약 테스트. 실제 Grok/외부 네트워크 0회.

## 검증 결과

- `uv run pytest -q`: 84개 통과
- `uv run ruff check .`: 통과
- `uv run mypy src tests`: 50개 소스 파일 검사 통과
- 기존 설명·QA, JSON·NDJSON 스트림 회귀 테스트: 전부 통과
- 실제 Grok/xAI 및 테스트 외부 네트워크 호출: 0회
---

# Issue #38 Worklog — 평가·진단·교정·메모리

기준 브랜치: `origin/develop` (`7b18536`, #79·learnerConfidence enum 반영)

## 작업 결과

- 2026-07-28 16:45 KST: 최신 develop에서
  `feature/38-assessment-diagnosis` 생성.
- 2026-07-28 19:32 KST: #79가 포함된 최신 develop에 rebase하고
  `learnerConfidence=LOW|MEDIUM|HIGH` 및 `models/base.py` 공통 계약 구조와
  #38 미병합 코드를 정렬했습니다.
- `/internal/ai/quiz-assessment`가 채점 결과를 근거 제한 평가·메모리 후보로
  변환하며, 단일 결과로 수준·성격·능력·장기 오개념을 확정하지 않도록
  한국어 프롬프트에서 제한합니다.
- `/internal/ai/diagnosis`가 오답·평가·페이지 근거로 짧은 진단 질문을
  생성하며 정답·modelAnswer·전체 해설 선공개를 금지합니다.
- 두 파이프라인은 `reasoning_effort=high`와 각 45초 timeout을 사용합니다.
- RepairAgent가 `DIAGNOSIS_ANSWER_SUBMITTED` 스텁을 대체합니다. pending
  diagnosis가 없거나 ID가 다르면 Policy가 거부하고, 성공하면
  `{pageStatus: REPAIR_COMPLETED, pendingDiagnosis: null}`을 제안합니다.
- `BUILD_MEMORY_CANDIDATE`, `PROMOTE_MEMORY`를 결정적 도구로 연결했습니다.
  type·content·confidence·evidence를 검증하며 PROMOTE는 confidence 0.7 이상,
  중복 없는 evidence reference 문자열 2개 이상을 요구합니다. 두 도구는
  primary action이 있는 Plan의 보조 action으로만 허용합니다.
- `docs/prompt-assets.md`가 develop에 없어 사용자 제공 자산 §3의 Repair
  규율과 `agent-system-spec` §4.6~4.9만 사용했습니다.

## #36 확정(2026-07-28 서면 승인)

### quiz-assessment

- 요청: 계약 §6.3의 `quizResult`, `quizItems`, `studentAnswers`,
  `pageContext`, `learnerMemoryDigest`.
- 응답: `understandingSummary`, `strengths[]`, `weaknesses[]`,
  `suspectedMisconceptions[]`, `recommendedNextDirection`,
  `memoryCandidates[]{type,content,confidence}`, `evidence[]`, `usage`.

### diagnosis

- 요청: 계약 §6.4의 `quizAssessment`, `quizResult`, `wrongItems[]`,
  `pageContext`, `learnerMemoryDigest`.
- 응답: `focusConcepts[]`, `suspectedMisconceptions[]`,
  `diagnosticPrompt`, `evidence[]`, `repairHint`, `usage`.

### Repair·메모리 turn 도구

- `REPAIR_MISCONCEPTION` args: `{diagnosisId}`. 이벤트 payload와 snapshot
  `pendingDiagnosis.diagnosisId`가 모두 일치해야 합니다.
- `BUILD_MEMORY_CANDIDATE`, `PROMOTE_MEMORY` args:
  `{type, content, confidence, evidence[]}`.
- 허용 type: `STRENGTH`, `WEAKNESS`, `MISCONCEPTION`, `PREFERENCE`.
- turn `memoryCandidates[]`에는 위 필드와
  `promotionRequested:boolean`을 반환합니다. 실제 저장·승격은 Spring
  책임입니다.

### 확정 4건

1. 메모리 승격 `confidence`는 0~1 숫자이며 DEC-012의 `>=0.7` 기준을
   적용합니다.
2. 독립 근거는 중복 없는 evidence reference 문자열 최소 2개입니다.
   `source`·`sessionId`·`observedAt` 구조화는 후속 이슈로 보류합니다.
3. turn `memoryCandidates[]`는
   `{type, content, confidence, evidence[], promotionRequested}`이며 실제
   저장·승격은 Spring 책임입니다.
4. `BUILD_MEMORY_CANDIDATE`와 `PROMOTE_MEMORY`는 보조 action 전용입니다.
   primary action 없이 메모리 도구만 있는 Plan은 Policy가 거부합니다.

## 이슈 #38 체크리스트 매핑

- [x] quiz-assessment structured endpoint.
- [x] diagnosis structured endpoint.
- [x] RepairAgent turn 도구와 pending diagnosis 정책.
- [x] LearnerMemory candidate·promotion 도구.
- [x] 진단 전 교정, 승격 근거·confidence, 금지 type 정책.
- [x] 메모리 도구 단독 Plan 거부.
- [x] FakeLlm 계약 테스트. 실제 Grok/외부 네트워크 0회.

## 검증 결과

- `uv run pytest -q`: 92개 통과
- `uv run ruff check .`: 통과
- `uv run mypy src tests`: 55개 소스 파일 검사 통과
- 기존 설명·QA·Quiz/Grader 및 JSON·NDJSON 계약 테스트: 전부 통과

## PR 본문 초안

```markdown
## 변경 요약

- POST /internal/ai/quiz-assessment와 POST /internal/ai/diagnosis
- RepairAgent turn 도구와 REPAIR_COMPLETED statePatch
- 메모리 후보·승격 도구와 DEC-012 Policy gate
- Assessment/Diagnosis high reasoning profile

## 계약 상태

#36 서면 확정 4건을 코드·계약 문서에 반영 완료.

## 검증

- pytest 92 passed
- ruff/mypy passed
- 실제 Grok/xAI 및 테스트 외부 네트워크 호출 0회

Closes #38
```

---

# Planner 축약 컨텍스트 Phase Worklog

기준 브랜치: `origin/develop` (`92d7577`, 2026-07-28 rebase)

## 작업 결과

- 2026-07-28 05:25 KST: 최신 develop에서
  `feature/planner-slim-context`를 생성했습니다.
- `PlanContext`를 추가하고 Orchestrator의 Plan 생성 프롬프트에만
  직렬화하도록 변경했습니다. PolicyVerifier와 Explainer/QaAgent는 기존
  전체 `AgentContext`를 계속 사용합니다.
- Plan 입력은 다음과 같이 제한했습니다.
  - session은 `currentPage`, `pageStatus`만 포함
  - 현재 페이지는 앞 500자 `pageTextPreview`, 인접 페이지는 존재 여부만 포함
  - 최근 메시지는 최근 3개의 `role`, 앞 120자 `content`만 포함
  - QA 문맥은 `threadRef`, 요약 존재 여부만 포함
  - 평가 요약은 최신 1개만 포함
  - pending diagnosis는 존재 여부와 `diagnosisId`, latest repair는 존재
    여부만 포함
- 테스트에서 페이지 전문, 오래된·전체 메시지, QA 전문, 학생 진단 답안,
  교정 원문이 Plan 직렬화에 포함되지 않음을 확인했습니다.
- 기존 Plan 정책 검증·page/detailLevel 보정과 Agent 전체 문맥 전달 테스트는
  그대로 통과했습니다.

## 검증 결과

- `uv run pytest -q`: 54개 통과
- `uv run ruff check .`: 통과
- `uv run mypy src tests`: 41개 소스 파일 검사 통과
- 테스트 중 실제 Grok/xAI 및 외부 네트워크 호출: 0회

## 블로커 보완·최신 develop 정렬 (2026-07-28)

- #25의 turn deadline·NDJSON 경로, learnerConfidence enum, #79의
  QuizAgent·GraderAgent·`models/base.py`, #80의 평가·진단·Repair·메모리
  도구가 포함된 최신 develop에 rebase했습니다.
- Orchestrator는 최신 `create_plan(context, deadline)`을 유지하며 Plan LLM
  호출에만 `PlanContext`를 전달합니다. Policy와 Explainer·QA·Quiz·Repair는
  기존 전체 `AgentContext`를 사용합니다.
- `DIAGNOSIS_ANSWER_SUBMITTED`의 `eventPayload.answer`를 Plan 입력에서
  제거했습니다. 라우팅에 필요한 `diagnosisId`만 유지합니다.
- 최신 `quizAssessments`는 dict 전체 대신 `understandingSummary`,
  `recommendedNextDirection`, `weaknesses`만 선별합니다.
- `QUIZ_TYPE_SELECTED`는 선택 유형과 bounded page preview만 Plan에 전달하며,
  RepairAgent는 Plan에서 제거된 답안·전체 페이지·진단 문맥을 실행 단계에서
  그대로 받는 것을 회귀 테스트로 확인했습니다.
- 보완 후 `uv run pytest -q` 101개, ruff, mypy(56개 소스 파일)가
  통과했습니다.
- 테스트 중 실제 Grok/xAI 및 외부 네트워크 호출은 0회입니다.

## PR 본문

```markdown
## 변경 요약

- Orchestrator Plan 호출 전용 `PlanContext`를 추가했습니다.
- 페이지 전문·전체 메시지·교정/진단 원문 대신 bounded preview와 존재 여부만
  플래너에 전달합니다.
- 진단 답변 원문을 Plan payload에서 제거하고 최신 평가도 승인된 요약 필드
  3종만 전달합니다.
- Policy 검증과 Explainer·QA·Quiz·Repair 실행은 기존 전체
  `AgentContext`를 유지합니다.

## 검증

- Plan 직렬화 길이·진단 답변·평가 상세 원문 부재 단위 테스트
- 기존 Plan 검증·보정 및 turn 계약 회귀 테스트
- `uv run pytest -q` — 101 passed
- `uv run ruff check .` — passed
- `uv run mypy src tests` — passed
- 실제 Grok/xAI 및 테스트 외부 네트워크 호출 0회
```
