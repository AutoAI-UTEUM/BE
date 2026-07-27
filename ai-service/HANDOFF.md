# AI Service Issues #9 and #5 Handoff

대상: 한승준(Main Service 담당), 이슈 #7(Material 추출 연동), #10(AiClient 연동
검증), #11(CI)

이 문서는 이슈 #9에서 만든 FastAPI 부트스트랩과 이슈 #5의 PDF 추출 API
실행·계약·CI 인수인계 정보입니다. 기존 health/turn/error curl 응답은
2026-07-25에 로컬 `127.0.0.1:8000`에서, extract 응답은 2026-07-26에 격리
smoke 포트 `127.0.0.1:8015`에서 실제로 실행해 확인했습니다.

## 1. 로컬 실행

저장소 루트에서 다음 순서로 실행합니다.

```bash
cd ai-service
cp .env.example .env
uv sync --locked
uv run uvicorn edupilot_ai.factory:create_app \
  --factory \
  --host 127.0.0.1 \
  --port 8000
```

- 기본 로컬 주소: `http://127.0.0.1:8000`
- 반드시 `--factory`를 사용합니다. 모듈 레벨 `app` 인스턴스는 없습니다.
- `.env.example`은 가짜 값만 포함합니다. 실제 시크릿은 커밋하지 않습니다.

### 환경 변수

| 환경 변수 | 필수 | 기본값 | 설명 |
| --- | :---: | --- | --- |
| `EDUPILOT_INTERNAL_TOKEN` | Y | 없음 | Spring과 AI Service가 동일하게 사용하는 `X-Internal-Token` 값 |
| `XAI_API_KEY` | Y | 없음 | 현재 스텁에서는 사용하지 않지만 Settings 시작 검증에 필요 |
| `MODEL_NAME` | N | `grok-4.5` | 고정 응답의 `usage.model` 및 향후 LLM 프로필 모델명 |
| `TURN_TIMEOUT_SECONDS` | N | `180` | turn 전체 timeout |
| `TURN_FIRST_EVENT_TIMEOUT_SECONDS` | N | `30` | turn 첫 이벤트 timeout |
| `GRADE_TIMEOUT_SECONDS` | N | `90` | grade timeout |
| `QUIZ_ASSESSMENT_TIMEOUT_SECONDS` | N | `45` | quiz-assessment timeout |
| `DIAGNOSIS_TIMEOUT_SECONDS` | N | `45` | diagnosis timeout |
| `EXTRACT_TIMEOUT_SECONDS` | N | `120` | extract timeout |
| `EDUPILOT_UPLOAD_MAX_MB` | N | `45` | extract PDF 최대 크기(MiB), 계약 상한 45 |
| `EDUPILOT_EXTRACT_MAX_PAGES` | N | `300` | extract PDF 최대 페이지 수, 계약 상한 300 |
| `AGENT_REASONING_EFFORT` | N | `medium` | 기본 `AgentLlmProfile.reasoningEffort` |
| `AGENT_MAX_TOKENS` | N | `16384` | 기본 `AgentLlmProfile.maxTokens` |
| `AGENT_TEMPERATURE` | N | `null` | 선택적 temperature |

로컬에서는 `.env.example`의 다음 가짜 값을 그대로 사용할 수 있습니다.

```dotenv
EDUPILOT_INTERNAL_TOKEN=replace-with-local-internal-token
XAI_API_KEY=xai-example-not-a-real-key
MODEL_NAME=grok-4.5
```

## 2. curl 계약 확인

### GET /health 정상

```bash
curl --silent --show-error \
  http://127.0.0.1:8000/health
```

HTTP 200:

```json
{
  "status": "UP"
}
```

### POST /internal/ai/turn 정상

README와 동일한 요청 body입니다.

```bash
curl --silent --show-error \
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

HTTP 200:

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-local-1",
  "turnGoal": "ANSWER_USER_QUESTION",
  "actionsExecuted": [],
  "messages": [
    {
      "messageType": "SYSTEM",
      "content": "EduPilot AI turn stub is ready."
    }
  ],
  "statePatch": {},
  "uiActions": [],
  "memoryCandidates": [],
  "usage": {
    "model": "grok-4.5",
    "inputTokens": 0,
    "outputTokens": 0,
    "reasoningTokens": null
  }
}
```

### POST /internal/ai/extract 정상

테스트 코드로 생성한 2페이지 PDF(1페이지 텍스트, 2페이지 공백)를 사용했습니다.
기본 실행 포트에서는 URL의 `8015`를 `8000`으로 바꿉니다.

```bash
curl --silent --show-error \
  -H 'X-Internal-Token: phase1-local-token' \
  -H 'X-Trace-Id: phase1-extract-handoff' \
  -F 'file=@/absolute/path/to/lesson.pdf;type=application/pdf' \
  http://127.0.0.1:8015/internal/ai/extract
```

HTTP 200:

```json
{
  "schemaVersion": "1.0",
  "pageCount": 2,
  "pages": [
    {
      "pageNumber": 1,
      "text": "EduPilot extraction handoff"
    },
    {
      "pageNumber": 2,
      "text": ""
    }
  ]
}
```

### X-Internal-Token 누락

`X-Trace-Id`만 전달해 trace 전파도 함께 확인합니다.

```bash
curl --silent --show-error \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: handoff-auth-trace' \
  -d '{
    "schemaVersion": "1.0",
    "turnId": "turn-local-1",
    "session": {"sessionId": 100},
    "event": {"eventType": "USER_QUESTION", "payload": {}},
    "context": {}
  }' \
  http://127.0.0.1:8000/internal/ai/turn
```

HTTP 401:

```json
{
  "schemaVersion": "1.0",
  "error": {
    "code": "AI_INTERNAL_AUTH_FAILED",
    "category": "AUTH",
    "message": "Internal service authentication failed.",
    "retryable": false
  },
  "traceId": "handoff-auth-trace"
}
```

### 잘못된 body: schemaVersion 누락

```bash
curl --silent --show-error \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Token: replace-with-local-internal-token' \
  -H 'X-Trace-Id: handoff-schema-trace' \
  -d '{
    "turnId": "turn-local-1",
    "session": {"sessionId": 100},
    "event": {"eventType": "USER_QUESTION", "payload": {}},
    "context": {}
  }' \
  http://127.0.0.1:8000/internal/ai/turn
```

HTTP 422:

```json
{
  "schemaVersion": "1.0",
  "error": {
    "code": "AI_REQUEST_INVALID",
    "category": "SCHEMA",
    "message": "Request does not match the internal API schema.",
    "retryable": false
  },
  "traceId": "handoff-schema-trace"
}
```

## 3. Spring AiClient 매핑

AI Service 오류의 `message`를 외부 사용자에게 그대로 노출하지 않고,
`error.category`를 기준으로 Spring의 안정된 오류로 변환합니다.

| AI category | Spring ErrorCode | 외부 HTTP | 처리 |
| --- | --- | :---: | --- |
| `AUTH` | `INTERNAL_SERVER_ERROR` | 500 | 내부 토큰/배포 설정 오류로 기록하고 외부에는 인증 세부정보를 숨김 |
| `TIMEOUT` | `AI_SERVICE_TIMEOUT` | 504 | timeout으로 변환 |
| `SCHEMA` | `AI_RESPONSE_INVALID` | 502 | AI 요청/응답 계약 오류로 변환 |
| `POLICY` | `AI_POLICY_REJECTED` | 502 | 정책 거부로 변환 |
| `INTERNAL` | `AI_SERVICE_UNAVAILABLE` | 503 | AI Service 일시 장애로 변환 |

- `retryable=false`이면 Spring에서 재시도하지 않습니다.
- `retryable=true`인 `TIMEOUT`과 일부 `INTERNAL`만 계약에서 허용하는 최대 횟수
  안에서 재시도합니다.
- Spring이 `X-Trace-Id`를 보내면 AI Service는 같은 값을 응답 헤더에 넣고,
  오류 envelope의 `traceId`에도 그대로 사용합니다.
- Spring이 `X-Trace-Id`를 보내지 않으면 AI Service가 새 trace ID를 생성하고
  응답 헤더와 오류 envelope에 사용합니다.
- extract 호출 timeout은 계약대로 120초를 사용합니다. 응답 envelope의
  `retryable=false`인 `EXTRACTION_FAILED`, `PAGE_LIMIT_EXCEEDED`,
  `FILE_TOO_LARGE`는 같은 파일을 자동 재시도하지 않습니다.
- extract 성공 응답은 `pageCount == pages.length`, `pageNumber`가 1부터
  연속인지 검증한 후 Spring이 `material_pages`에 저장하고 READY로 전이합니다.
  FastAPI는 원본이나 추출 텍스트를 영속화하지 않습니다.

### 현재 AI Service 오류 code

| code | 발생 조건 | category |
| --- | --- | --- |
| `AI_INTERNAL_AUTH_FAILED` | 내부 토큰 누락 또는 불일치 | `AUTH` |
| `AI_REQUEST_INVALID` | 요청 body 검증 실패 | `SCHEMA` |
| `AI_INTERNAL_ERROR` | 처리되지 않은 서버 예외 | `INTERNAL` |
| `EXTRACTION_FAILED` | 손상·암호화·전 페이지 텍스트 없음 | `INTERNAL` |
| `PAGE_LIMIT_EXCEEDED` | 설정된 최대 페이지 수 초과 | `SCHEMA` |
| `FILE_TOO_LARGE` | 설정된 최대 PDF 바이트 초과 | `SCHEMA` |

## 4. 이슈 #11 CI 워크플로 예시

아래 내용은 인수인계 예시일 뿐이며 이번 PR에서
`.github/workflows/ai-service-ci.yml`을 수정하지 않습니다. 기존의
`requirements.txt` 기반 임시 감지를 `pyproject.toml`/`uv.lock` 기준으로
교체할 때 사용할 수 있습니다.

```yaml
name: AI Service CI

on:
  pull_request:
    paths:
      - "ai-service/**"
      - ".github/workflows/ai-service-ci.yml"
  push:
    branches:
      - main
      - develop
    paths:
      - "ai-service/**"
      - ".github/workflows/ai-service-ci.yml"

permissions:
  contents: read

concurrency:
  group: ai-service-ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  ai-service-build:
    name: ai-service-build
    runs-on: ubuntu-latest
    timeout-minutes: 15

    steps:
      - name: Checkout repository
        uses: actions/checkout@v6

      - name: Set up Python 3.14
        uses: actions/setup-python@v6
        with:
          python-version: "3.14"

      - name: Install uv and restore dependency cache
        uses: astral-sh/setup-uv@08807647e7069bb48b6ef5acd8ec9567f424441b # v8.1.0
        with:
          enable-cache: true
          cache-dependency-glob: "ai-service/uv.lock"
          working-directory: ai-service

      - name: Sync locked dependencies
        working-directory: ai-service
        run: uv sync --locked

      - name: Test
        working-directory: ai-service
        run: uv run pytest

      - name: Lint
        working-directory: ai-service
        run: uv run ruff check .

      - name: Type check
        working-directory: ai-service
        run: uv run mypy
```

## 5. 알려진 제약과 후속 범위

- `/internal/ai/turn`은 이슈 #9의 고정 스텁 응답입니다. 실제 Orchestrator와
  에이전트 실행은 Epic 5에서 구현합니다.
- `/health`는 프로세스 liveness만 확인합니다. 외부 의존성 readiness는 Epic 8
  범위입니다.
- 실제 Grok/xAI HTTP 클라이언트는 없으며 로컬·테스트 실행 중 외부 네트워크를
  호출하지 않습니다.
- extract는 텍스트 레이어만 처리합니다. OCR은 범위 밖이며 전 페이지가 공백이면
  `EXTRACTION_FAILED`로 반환합니다.
