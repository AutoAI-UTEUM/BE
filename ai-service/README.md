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

## 지연 측정 (1단계: 로그 계측)

모델·프롬프트·출력 상한·캐시 설정·재시도 정책을 바꾸지 않고 기준선을 수집합니다.
아래 값은 내부 JSON 로그에만 추가되며 API 응답과 NDJSON 6이벤트 계약은 그대로입니다.
PDF/질문/답변/추론 원문, 파일 ID, base64, 인증 헤더는 새 계측에 기록하지 않습니다.

| 로그 `message` | 측정 범위 / 주요 필드 |
| --- | --- |
| `turn planning started` / `turn planning finished` | ContextBuilder 이후 Plan 해석 시작부터 종료. `planSource=LLM/DETERMINISTIC`, `durationMs`, 성공 시 `plannerAttempts`(합성은 0). 정책 검증은 제외 |
| `planner attempt completed` / `planner attempt failed` | Planner의 개별 스키마 생성 시도. `attempt`, `durationMs`, `status`, `errorCode`. 재생성 시 첫 실패는 `RETRYING` |
| `turn policy verification finished` | 기존 Policy 검증만의 `durationMs`, `status`. 검증 규칙 자체는 변경하지 않음 |
| `xAI first content available` | LLM 브리지 호출 시작부터 첫 유효 본문 델타까지 `firstContentMs`. 논리 호출당 최대 1줄 |
| `xAI chat completion finished` | 기존 호출 결과 로그. `durationMs`, `attemptDurationMs`, `providerStatusCode`, 입력/출력/추론/캐시 토큰, 요청 규모, 스트림 본문 시각 추가 |
| `turn first content available` | API의 턴 스트림 소비 시작부터 첫 유효 `content_delta`가 Spring으로 나가기 직전까지 `firstContentMs` |
| `turn stream completed` / `turn stream failed` / `turn stream cancelled by client` | 턴 전체 `durationMs`, `eventType`, `streaming=true`, 관측한 본문 시각. `completed`를 관측했으면 전송 직전 `resultReadyMs`도 기록 |
| `turn completed` / `turn failed` | JSON 턴의 처리 완료 시간 `durationMs`, `eventType`, `streaming=false`. 첫 본문 시각은 없음 |

### 시간과 호출 연결

- 모든 경과 시간은 프로세스의 단조 시계 기준 ms입니다. `firstContentMs`,
  `lastContentMs`는 각 로그 범위의 시작점 기준이며, `contentSpanMs`는 첫/마지막
  유효 본문 델타 관측 사이의 시간입니다. 상태·heartbeat·thought·빈/공백 델타는
  제외합니다. 관측하지 못한 시각은 0으로 만들지 않고 생략합니다.
- 이 시각은 AI가 본문을 확인한 시점이지 FE가 화면에 그린 시점이 아닙니다. 본문
  관측 구간에는 네트워크와 소비자의 처리 지연도 포함될 수 있으므로 순수 모델
  디코딩 시간으로 해석하지 않습니다. 실패·취소 로그의 마지막 본문은 **부분 결과**입니다.
- 퀴즈/노트처럼 `content_delta`가 없는 NDJSON 턴은 `resultReadyMs`로 준비 완료를
  측정합니다. heartbeat를 첫 답변으로 세지 않습니다. 퀴즈의 API는 NDJSON이어도
  xAI 생성 호출 자체의 `streaming`은 false일 수 있습니다.
- `traceId`, `turnId`, `llmCallId`와 있는 경우 `actionId`로 연결합니다. Planner는
  `responseModel=TurnPlan`과 Planner 시도 로그로 식별합니다. 기존 스트림 경로는
  공급자 로그에 actionId를 바인딩하지 않으므로 해당 turn의 `eventType`과 에이전트
  완료 로그를 함께 대조합니다. 없는 연결 필드를 있다고 가정하지 않습니다.
  비동기 대화 요약은 `responseModel`과 별도 호출 ID로 구분하고 턴 시간에 더하지 않습니다.
- `llmCallId`는 브리지 논리 호출당 하나입니다. 내부 전송 재시도는 같은 ID와 증가한
  `attempt`를 씁니다. 기존 `durationMs`는 논리 호출 시작부터 누적, `attemptDurationMs`는
  해당 전송 시도부터의 경과입니다. 재시도 로그들의 누적 duration을 합산하지 않습니다.
  스키마 재생성은 새 논리 호출 ID이며, Spring 재시도는 별도의 turnId 연결이 필요합니다.
- xAI·Planner·에이전트·턴의 시간은 중첩되므로 모두 더하지 않습니다. 각 범위별로
  비교하고, `SUCCESS/FAILED/CANCELLED`와 내부 HTTP 상태도 구분합니다.

### 토큰과 입력 규모

- `inputTokens`, `outputTokens`, `reasoningTokens`, `cachedInputTokens`는
  `xAI chat completion finished`에 각 공급자 시도에서 실제 받은 usage만 기록합니다.
  첫 본문 로그에 usage를 중복 기록하지 않으며 재시도 전체의 합계가 아닙니다.
  미제공·잘못된 값은 생략하며 명시된 정수 0만 0으로 기록합니다. 캐시 토큰이
  알려진 전체 입력 토큰보다 크면 캐시 값만 생략합니다. 본 기능의 성공/실패는 바꾸지 않습니다.
- 캐시 원본은 Chat Completions의 `usage.prompt_tokens_details.cached_tokens`,
  Responses의 `usage.input_tokens_details.cached_tokens`입니다.
  [xAI 공식 usage 문서](https://docs.x.ai/developers/advanced-api-usage/prompt-caching/usage-and-pricing)를
  기준으로 하며 공용 응답 usage 스키마에는 캐시 필드를 추가하지 않습니다.
- `inputTextChars`는 system/user/history 등 전달 메시지 텍스트의 문자 수입니다.
  서비스가 JSON으로 직렬화한 문맥의 문법 문자도 포함하고, 파일 ID가 가리키는 PDF
  본문·이미지/base64는 포함하지 않습니다. **문자 수를 공급자 입력 토큰 수로 해석하지 않습니다.**
- `fileAttached`, `fileCount`, `imageCount`, `messageCount`, `responseModel`,
  `requestedModel`, `reasoningEffort`, `maxOutputTokens`로 호출 조건을 기록합니다.
  성공 로그의 기존 `model`은 공급자가 반환한 모델이며, 실패 시에는 요청 모델일 수 있습니다.

### 새 기준선 수집

계측만 배포한 뒤 설명·QA·OX·MCQ를 구분하여 같은 자료·페이지·문맥·모델·추론 설정·
출력 상한·문항 수·동시 요청 수에서 반복 관측합니다. 실제 LLM 재호출은 별도 승인된
호출 예산 안에서 수행합니다. 과거 로그에 누락된 값은 소급 복원되지 않습니다.
첫 호출이라고 cold cache로 단정하지 말고 실제 cachedInputTokens로 구분합니다.
소수 표본은 중앙값·범위를 제시하고 개선율이나 p95를 확정하지 않습니다.

Spring의 requestId/바깥 재시도 번호·첫 본문 수신/전송 시각, FE의 실제 렌더 시각은
각 담당의 후속 계측입니다. 이번 변경만으로 브라우저 체감 지연을 전부 측정했다고
보고하지 않습니다. 운영 성능 측정·캐시 최적화·Planner 경량화는 아직 수행하지 않았습니다.

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
