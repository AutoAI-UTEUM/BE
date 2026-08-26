# EduPilot AI Service

FastAPI 기반 내부 AI 서비스입니다. 현재 범위는 health, 내부 토큰 인증, 표준 오류
형식, 비스트리밍 turn 오케스트레이션, LLM 격리 인터페이스와 xAI
structured-output HTTP 어댑터, 결정적 PDF 텍스트 추출까지입니다. 설명과
질의응답 turn은
`ContextBuilder → Orchestrator → PolicyVerifier → ToolDispatcher`를 거치며,
퀴즈 생성과 오개념 교정 실행은 각각 이슈 #31, #38의 스텁으로 남아 있습니다.

## 요구 사항

- Python 3.14.x
- [uv](https://docs.astral.sh/uv/)

## 로컬 실행

```bash
cd ai-service
cp .env.example .env
uv sync --locked
uv run uvicorn edupilot_ai.factory:create_app --factory --host 127.0.0.1 --port 8000
```

`.env.example` 값은 예시이며 실제 시크릿이 아닙니다. 로컬 `.env`에는 안전한 별도 값을
설정하고 커밋하지 않습니다.

health 확인:

```bash
curl --fail http://127.0.0.1:8000/health
```

## 검증

로컬과 GitHub Actions는 같은 uv locked 환경과 명령을 사용합니다.

```bash
uv sync --locked --dev
uv run --locked ruff format --check src tests demo.py
uv run --locked ruff check src tests demo.py
uv run --locked mypy src tests demo.py
uv run --locked pytest -q
```

pytest 기본 설정은 `live` 마커를 제외하므로 CI에서 실제 외부 AI를 호출하지 않습니다.

turn 확인:

```bash
curl --fail \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Token: replace-with-local-internal-token' \
  -d '{
    "schemaVersion": "1.0",
    "turnId": "turn-local-1",
    "session": {
      "sessionId": 100,
      "userId": 1,
      "materialId": 10,
      "currentPage": 3,
      "pageStatus": "NOT_EXPLAINED"
    },
    "event": {
      "eventType": "USER_QUESTION",
      "payload": {"message": "편차가 뭔지 모르겠어"}
    },
    "context": {
      "xaiFileId": null,
      "currentPageText": "편차는 관측값과 평균의 차이입니다.",
      "previousPageText": null,
      "nextPageText": null,
      "recentMessages": [],
      "qaThreadDigest": null,
      "quizAssessments": [],
      "learnerMemoryDigest": null,
      "learnerLevel": null,
      "learnerConfidence": null,
      "pendingDiagnosis": null,
      "latestRepair": null,
      "memory": {"temporaryCandidates": []}
    }
  }' \
  http://127.0.0.1:8000/internal/ai/turn
```

PDF 페이지 텍스트 추출 확인:

```bash
curl --fail \
  -H 'X-Internal-Token: replace-with-local-internal-token' \
  -F 'file=@/absolute/path/to/lesson.pdf;type=application/pdf' \
  http://127.0.0.1:8000/internal/ai/extract
```

추출 요청은 `application/pdf`인 `.pdf` 파일만 허용하며 `%PDF-` 매직 바이트,
`EDUPILOT_UPLOAD_MAX_MB`(기본·최대 45), `EDUPILOT_EXTRACT_MAX_PAGES`(기본·최대
300)를 순서대로 검증합니다. 응답은 1-based `pageNumber`와 정제된 페이지 텍스트를
포함합니다. 전 페이지에 텍스트 레이어가 없으면 스캔본으로 분류해 거부합니다.

이 호출은 설정된 xAI endpoint를 사용합니다. 개발·CI 검증은 `FakeLlm` 또는
`respx` mock만 사용하며 실제 provider 호출을 포함하지 않습니다.

## CLI 데모 (설계자·비개발자용)

[uv 설치 안내](https://docs.astral.sh/uv/getting-started/installation/)에 따라 `uv`를
설치하고, 팀에서 `ai-service/.env`를 받아 `XAI_API_KEY`가 설정된 상태로 실행합니다.
서버나 내부 토큰, 포트 설정 없이 PDF 파일 하나로 에이전트를 직접 체험할 수 있습니다.

```bash
cd ai-service
uv run python demo.py outline ~/자료/강의.pdf
uv run python demo.py criteria ~/자료/강의.pdf
```

```text
추출 중... 19페이지
개요 생성 중... 완료 9.0s
지표 생성 중... 완료 7.4s
{"schemaVersion": "1.0", ...}
```

각 에이전트 실행에는 실제 LLM 호출과 수십 원 수준의 비용이 발생할 수 있습니다.
`.env`는 시크릿 파일이므로 절대 커밋하지 않습니다.

## 검증

모든 PR 게이트는 실제 Grok과 외부 네트워크 호출 없이 실행됩니다.

```bash
uv run pytest
uv run ruff check .
uv run mypy
```

## 구조

- `src/edupilot_ai/factory.py`: `create_app()`과 app-scoped lifespan
- `src/edupilot_ai/settings.py`: 환경 변수와 `AgentLlmProfile`
- `src/edupilot_ai/core/`: 표준 오류 및 내부 토큰 미들웨어
- `src/edupilot_ai/llm/`: `LlmBridge` Protocol과 xAI HTTP 어댑터
- `src/edupilot_ai/extraction/`: 영속화 없는 `pypdf` 추출 코어
- `src/edupilot_ai/orchestration/`: 문맥 구성, Plan, 정책 검증, 에이전트, 도구 실행
- `src/edupilot_ai/api/`: health, turn, extract 내부 API
- `tests/`: ASGITransport 계약 테스트와 `FakeLlm`

상태와 영속 데이터의 기준은 Spring/MySQL이며, 이 서비스는 자체 영속 저장소를 두지
않습니다.

xAI 어댑터의 와이어 테스트는 `respx`가 `https://api.x.ai`를 전부 가로채며 실제
네트워크를 사용하지 않습니다. 실제 자격 증명으로 실행하는 live 테스트는 없습니다.
