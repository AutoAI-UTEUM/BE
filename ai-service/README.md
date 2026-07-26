# EduPilot AI Service

FastAPI 기반 내부 AI 서비스입니다. 현재 범위는 health, 내부 토큰 인증, 표준 오류
형식, 고정 turn 응답, LLM 격리 인터페이스와 xAI structured-output HTTP
어댑터, 결정적 PDF 텍스트 추출까지입니다. `/internal/ai/turn`은 아직 이
어댑터를 호출하지 않는 고정 스텁이며 Orchestrator와 에이전트 실행은 포함하지
않습니다.

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

turn 스텁 확인:

```bash
curl --fail \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Token: replace-with-local-internal-token' \
  -d '{
    "schemaVersion": "1.0",
    "turnId": "turn-local-1",
    "session": {"sessionId": 100},
    "event": {"eventType": "USER_QUESTION", "payload": {}},
    "context": {}
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
- `src/edupilot_ai/api/`: health, turn 스텁, extract 내부 API
- `tests/`: ASGITransport 계약 테스트와 `FakeLlm`

상태와 영속 데이터의 기준은 Spring/MySQL이며, 이 서비스는 자체 영속 저장소를 두지
않습니다.

xAI 어댑터의 와이어 테스트는 `respx`가 `https://api.x.ai`를 전부 가로채며 실제
네트워크를 사용하지 않습니다. 실제 자격 증명으로 실행하는 live 테스트는 없습니다.
