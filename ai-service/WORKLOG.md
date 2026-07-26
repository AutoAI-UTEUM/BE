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

# Issue #5 Worklog

기준 브랜치: `origin/develop` (`ddaecd9`)

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

# Issue #23 Partial Worklog

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

## 완료된 부분

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

## 미완·미착수

- [ ] ContextBuilder — 시간 초과로 미착수 상태로 되돌림.
- [ ] Orchestrator Plan 생성, schema 1회 재생성 fallback — 미착수.
- [ ] Policy/Verifier — 허용 도구, pipeline 전용 도구, qaThreadMode,
  intervention budget 검증 — 미착수.
- [ ] ToolDispatcher — 순차 실행, 부분 실패, statePatch 병합 — 미착수.
- [ ] ExplainerAgent와 분리 프롬프트 — 미착수.
- [ ] QaAgent START_NEW/FOLLOW_UP, latestRepair, 근거 부족 처리 — 미착수.
- [ ] QUIZ_TYPE_SELECTED·DIAGNOSIS_ANSWER_SUBMITTED stub을 새 pipeline에 연결 —
  미착수. 현재 이슈 #9 고정 turn stub 유지.
- [ ] turn 응답 usage 합산과 statePatch 허용목록 검증 — 미착수.
- [ ] FakeLLM 기반 turn 계약 테스트 — 미착수.
- [ ] Phase 3 테스트 보강과 #25 사전 조사 — 남는 시간이 없어 미착수.

## 검증 결과

- `uv sync --locked`: 성공 (CPython 3.14.6, respx 0.23.1)
- `uv run pytest -q`: 13개 통과
- `uv run ruff check .`: 통과
- `uv run mypy`: 24개 소스 파일 검사 통과
- 테스트 중 실제 Grok 및 외부 네트워크 호출: 0회

## 이슈 체크 대기

- 이슈 #23의 각 체크박스는 pipeline 전체 단위로 작성되어 있으며, 이번 부분
  완료 범위만으로 완전히 충족된 항목이 없다. 완료되지 않은 항목은 `[x]`로
  바꾸지 않는다.
- PR은 “계약 v0.4 기준 부분 선구현 — #27 승인 후 리뷰”를 명시한 Draft로 생성한다.

## 미결 질문

1. 전체 마감이 `<YYYY-MM-DD HH:00>` 자리표시자로 남아 절대 마감은 계산할 수
   없었다. Phase 2는 명시된 4시간 상한을 적용해 중단했다.
2. #27이 미승인인 상태에서 qaThread의 `threadRef` 생성 주체·형식과
   `statePatch` 세부 JSON Schema가 확정되지 않았다.
3. 이슈 #23은 LlmBridge “재시도”를 요구하지만 v0.4는 Spring이
   `retryable=true`만 최대 1회 재시도하고 SCHEMA는 Orchestrator 내부 1회
   재생성으로 소진한다고 규정한다. provider adapter 자체 HTTP 재시도를 둘지
   #27에서 명확히 해야 한다. 이번 어댑터는 중복 비용을 피하려고 분류만 하고
   자동 재시도하지 않는다.
4. Phase 3도 독립 브랜치·PR이 필요한지, 아니면 Phase 1·2 브랜치에 각각 보강을
   추가하는지 지시가 없다. 분리 원칙을 어기지 않기 위해 미착수로 남겼다.
