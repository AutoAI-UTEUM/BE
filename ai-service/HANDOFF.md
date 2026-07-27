# AI Service Handoff

대상: 한승준(Main Service 담당), 이슈 #7(Material 추출 연동), #10(AiClient 연동
검증), #11(CI), #26(SSE 중계)

이 문서는 이슈 #9의 FastAPI 부트스트랩, 이슈 #5의 PDF 추출 API와 이슈 #23의
현재 turn 상태를 함께 설명합니다. health/turn/error curl 응답은 2026-07-25에
로컬 `127.0.0.1:8000`에서, extract 응답은 2026-07-26에 격리 smoke 포트
`127.0.0.1:8015`에서 실제로 실행해 확인했습니다. 현재 turn 요청의 전체 DTO는
`README.md` 예시를 사용하며 정상 응답 내용은 LLM 결과에 따라 달라집니다.
NDJSON turn 스트림은 2026-07-27에 FakeLlm을 주입한 격리 uvicorn 포트
`127.0.0.1:8025`에서 실제로 확인했습니다.

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
| `XAI_API_KEY` | Y | 없음 | xAI OpenAI-compatible endpoint 인증 키 |
| `MODEL_NAME` | N | `grok-4.5` | LLM 프로필 모델명과 응답 model 검증 기준 |
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
| `ORCHESTRATOR_REASONING_EFFORT` | N | `low` | Plan용 프로필 |
| `EXPLAINER_REASONING_EFFORT` | N | `medium` | 설명용 프로필 |
| `QA_REASONING_EFFORT` | N | `medium` | QA용 프로필 |

로컬에서는 `.env.example`의 다음 가짜 값을 그대로 사용할 수 있습니다.

```dotenv
EDUPILOT_INTERNAL_TOKEN=replace-with-local-internal-token
XAI_API_KEY=xai-example-not-a-real-key
MODEL_NAME=grok-4.5
```

## 2. 이슈 #9 부트스트랩 curl 기록

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

아래는 이슈 #9 당시 고정 스텁의 역사적 검증 기록입니다. 현재 요청 DTO 예시는
`README.md`를 따릅니다.

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

## 3. 이슈 #26 내부 NDJSON → 외부 SSE 중계

Spring이 점진 응답을 원하면 기존 turn 요청에 다음 헤더를 추가합니다.

```http
Accept: application/x-ndjson
```

헤더가 없으면 기존 JSON `TurnResponse`가 유지됩니다. 아래 curl은
2026-07-27 로컬 FakeLlm uvicorn에서 실제 실행한 기록이며 xAI나 외부
네트워크를 호출하지 않았습니다.

```bash
curl --fail --silent --show-error --no-buffer \
  -H 'Accept: application/x-ndjson' \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Token: stream-smoke-token' \
  -H 'X-Trace-Id: stream-smoke-trace' \
  -d '{
    "schemaVersion": "1.0",
    "turnId": "turn-stream-smoke-1",
    "session": {
      "sessionId": 100,
      "userId": 1,
      "materialId": 10,
      "currentPage": 3,
      "pageStatus": "EXPLAINED"
    },
    "event": {
      "eventType": "USER_QUESTION",
      "payload": {"message": "편차가 무슨 뜻이야?"}
    },
    "context": {
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
  http://127.0.0.1:8025/internal/ai/turn
```

실제 응답(`Content-Type: application/x-ndjson`, 한 줄에 JSON 1개):

```jsonl
{"type":"status","stage":"PLANNING"}
{"type":"thought_summary","text":"학습 계획을 세우는 중입니다"}
{"type":"status","stage":"ANSWERING"}
{"type":"thought_summary","text":"3페이지 근거로 답변을 작성하는 중입니다"}
{"type":"content_delta","text":"편차는 "}
{"type":"content_delta","text":"관측값이 평균에서 얼마나 떨어져 있는지를 나타냅니다."}
{"type":"status","stage":"FINALIZING"}
{"type":"completed","result":{"schemaVersion":"1.0","turnId":"turn-stream-smoke-1","turnGoal":"ANSWER_USER_QUESTION","actionsExecuted":[{"actionId":"action-1","agent":"QaAgent","status":"SUCCESS"}],"messages":[{"messageType":"QA","content":"편차는 관측값이 평균에서 얼마나 떨어져 있는지를 나타냅니다."}],"statePatch":{"qaThread":{"mode":"START_NEW"}},"uiActions":[],"memoryCandidates":[],"usage":{"model":"grok-4.5","inputTokens":0,"outputTokens":0,"reasoningTokens":null}}}
```

### 이벤트 중계 규칙

| 내부 NDJSON | 외부 SSE |
| --- | --- |
| `status` | `event: status` + JSON `data` |
| `thought_summary` | `event: thought_summary` + JSON `data` |
| `content_delta` | `event: content_delta` + JSON `data` |
| `heartbeat` | SSE comment `: heartbeat` — `event`/`data` 없음 |
| `completed` | `event: completed` + 전체 `result` JSON |
| `error` | `event: error` + `code/category/message/retryable` JSON |

- NDJSON은 임의 HTTP 청크 경계가 아니라 줄바꿈 기준으로 파싱합니다. 한 줄을
  완성하기 전에는 JSON 파싱을 시도하지 않습니다.
- `completed`와 `error`는 상호 배타이며 정확히 1회, 마지막입니다.
- `content_delta` 누적 문자열과
  `completed.result.messages[].content`를 순서대로 이은 문자열이 같은지
  검증합니다.
- 메시지·statePatch의 확정 저장은 `completed.result` 전체 검증 뒤 정확히
  1회만 수행합니다. `error`, 연결 중단, completed 이전 청크는 저장하지
  않습니다.
- 내부 `heartbeat`는 10초 무이벤트 시 발행됩니다. Spring은 외부 SSE comment로
  바꾸며 FE 이벤트 핸들러로 전달하지 않습니다.
- stream HTTP 응답이 시작된 뒤의 AI 실패는 HTTP status 변경이 아니라 마지막
  `error` 이벤트로 전달됩니다. 인증·요청 schema처럼 스트림 시작 전 오류는
  기존 JSON 오류 envelope와 HTTP 401/422를 유지합니다.
- Spring이 연결을 닫으면 FastAPI의 상류 xAI 스트림도 취소됩니다. 별도 취소
  endpoint와 `Last-Event-ID` 재전송은 없습니다.

## 4. Spring AiClient 매핑

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

## 5. 이슈 #11 CI 워크플로 예시

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

## 6. 알려진 제약과 후속 범위

- `/internal/ai/turn`의 설명·질의응답 경로는 이슈 #23 비스트리밍
  오케스트레이션에 연결되어 있습니다. 퀴즈 생성과 오개념 교정 실행은 각각
  이슈 #31, #38의 고정 스텁입니다.
- `/health`는 프로세스 liveness만 확인합니다. 외부 의존성 readiness는 Epic 8
  범위입니다.
- extract는 텍스트 레이어만 처리합니다. OCR은 범위 밖이며 전 페이지가 공백이면
  `EXTRACTION_FAILED`로 반환합니다.
- xAI OpenAI-compatible HTTP 어댑터는 turn 경로에 연결되어 있습니다.
  `respx` 와이어 테스트와 `FakeLlm` 계약 테스트는 `api.x.ai` 요청을 전부
  가로채거나 대체하므로 테스트 중 외부 네트워크를 호출하지 않습니다.
- `/internal/ai/turn`은 `Accept: application/x-ndjson`일 때 Explainer·QA
  NDJSON 스트림을 반환하고, 그 외에는 기존 완성 JSON 응답을 유지합니다.
- 퀴즈·교정 스텁은 NDJSON 요청에서도 provider 본문 스트림을 사용하지 않고
  terminal `completed` 또는 `error`로 종료합니다.
