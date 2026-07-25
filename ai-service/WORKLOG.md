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
