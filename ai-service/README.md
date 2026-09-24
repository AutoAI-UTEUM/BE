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

### 첨부 문서 처리·비용 계측

추가 계측도 로그 전용입니다. API 응답 usage·프롬프트·Planner 판단·모델·출력 상한·
재시도·캐시 스위치 기본값을 바꾸지 않습니다.

| 필드 | 의미 |
| --- | --- |
| `requestedModel` / `providerModel` | 요청 모델 / xAI 응답에 실제 보고된 모델 식별자. 응답값이 없거나 올바른 식별자 형식이 아니면 `providerModel` 생략. `responseModel`은 `TurnPlan` 같은 출력 DTO 이름이며 모델명이 아님 |
| `numServerSideToolsUsed` | 해당 xAI usage의 `num_server_side_tools_used` 원값. 첨부 파일 수나 output 항목 수로 추정하지 않음 |
| `serverSideToolUsageDetails` | `server_side_tool_usage_details`에서 허용한 종류별 호출 수만 복사. 미제공 종류는 0으로 채우지 않음 |
| `costUsdTicks` | `usage.cost_in_usd_ticks` 정수 원값. 1 USD = 10^10 ticks. 환산·반올림·토큰 기반 추정 없이 기록 |
| `providerUsageFinal` | 관측한 usage가 단일 JSON 응답 또는 스트림 종료 이벤트까지 확인된 값인지 표시. 중간 스냅샷만 있으면 false, 관측한 usage 자체가 없으면 생략 |

- 종류별 카운터는 `web_search_calls`, `x_search_calls`, `code_interpreter_calls`,
  `file_search_calls`, `mcp_calls`, `document_search_calls`, `image_generation_calls`만
  허용합니다. 각 값은 음이 아닌 정수여야 하며 bool·문자열·소수는 무시합니다.
  도구 인자·검색어·검색 결과·페이지 본문은 기록하지 않습니다.
- xAI의 파일 첨부는 내부 문서 검색을 동반할 수 있습니다. **파일 1개 첨부 = 검색 1회가
  아닙니다.** 검색 횟수와 지연의 관계는 실측으로 확인하며, 입력 토큰 증가만으로 원인을
  확정하지 않습니다. 공급자가 보고한 요청 비용에는 내부 도구 작업이 포함되므로 이를
  별도 비용으로 다시 더하지 않습니다.
- 값은 `xAI chat completion finished`에 전송 시도당 한 번만 기록합니다. 스트림 usage는
  누적 스냅샷이므로 덧셈하지 않고 교체하며 첫 본문 로그에는 usage를 싣지 않습니다.
  집계 시 `(llmCallId, attempt)`로 중복을 제거합니다. 스키마 재생성은 다른 `llmCallId`입니다.
- `providerUsageFinal=true`는 턴 성공이나 전체 재시도의 비용 확보를 뜻하지 않습니다.
  완결된 공급자 응답이 로컬 스키마 검증에서 실패해도 해당 시도의 보고값은 남습니다.
  이전 시도에 usage가 없으면 전체 요청 비용은 미상이며, 알려진 값만 더한 결과를 전체
  비용으로 보고하지 않습니다. 기존 응답 `cost_usd_ticks`의 보수적인 null 처리는 유지합니다.
- 응답 중단으로 최종 usage를 못 받으면 도구 수·비용이 여전히 미수집일 수 있습니다.
  미수집/잘못된 값은 생략하고 명시된 정수 0만 0으로 기록합니다. 과거 측정의 누락된
  값은 이 변경으로 소급 복원되지 않습니다.

근거: xAI [비용 추적](https://docs.x.ai/developers/cost-tracking),
[도구 사용량](https://docs.x.ai/developers/tools/tool-usage-details),
[문서 첨부](https://docs.x.ai/developers/model-capabilities/files/chat-with-files).

### 새 기준선 수집

계측만 배포한 뒤 설명·QA·OX·MCQ를 구분하여 같은 자료·페이지·문맥·모델·추론 설정·
출력 상한·문항 수·동시 요청 수에서 반복 관측합니다. 실제 LLM 재호출은 별도 승인된
호출 예산 안에서 수행합니다. 과거 로그에 누락된 값은 소급 복원되지 않습니다.
첫 호출이라고 cold cache로 단정하지 말고 실제 cachedInputTokens로 구분합니다.
소수 표본은 중앙값·범위를 제시하고 개선율이나 p95를 확정하지 않습니다.

Spring의 requestId/바깥 재시도 번호·첫 본문 수신/전송 시각, FE의 실제 렌더 시각은
각 담당의 후속 계측입니다. 이번 변경만으로 브라우저 체감 지연을 전부 측정했다고
보고하지 않습니다. 실제 운영 성능은 별도 실측으로 판정하며 Planner 경량화는 이 단계의 범위가 아닙니다.

## 캐시 재사용 비교 (2단계: 내용 보존, dev opt-in)

설명/QA Planner와 Explainer·QaAgent·QuizAgent 호출에 한해 두 독립 스위치를 제공합니다.
기본값은 모두 `false`이며, 배포만으로 운영 프롬프트를 바꾸지 않습니다.
이는 **답변 결과를 재사용하는 캐시가 아닙니다**. 매 턴 기존 LLM 호출·Policy 검증은
그대로 수행하고 xAI가 동일한 입력 prefix를 재사용하기 쉽게 만드는 최적화입니다.

| AI 환경 변수 | 기본값 | 켰을 때 변경 |
| --- | --- | --- |
| `EDUPILOT_PROMPT_CACHE_LAYOUT_ENABLED` | `false` | system 그대로 → 첨부 PDF → 고정 페이지 근거 → 질문·대화·학습자 상태 순서 |
| `EDUPILOT_PROMPT_CACHE_ROUTING_ENABLED` | `false` | Chat Completions는 `x-grok-conv-id` 헤더, Responses는 `prompt_cache_key` 필드 추가 |

입력 배치는 기존 user JSON을 서로 키가 겹치지 않는 두 user 메시지로 나눕니다.
원래 객체의 모든 값·null·배열 순서·텍스트가 유지되고 system 지시문은 한 글자도 바꾸지
않습니다. 질문·turnId·요약·메모리·진단 등 변하는 값은 뒤쪽에 매번 새로 전달합니다.
PDF는 기존과 같은 file ID를 한 번만 첨부하며, `includeCurrentPage=false`에서는 여전히
첨부하지 않습니다. PDF 검색 결과 자체의 캐시 여부는 xAI 내부 동작이므로 보장하지 않습니다.
모델·추론 강도·출력 상한·문항 수·출제 범위·퀴즈 제안 판단·timeout·재시도·wire 계약은
변경하지 않습니다. Note·Repair·outline 등 나머지 호출에는 이 실험을 적용하지 않습니다.

키는 버전·사용자·세션·자료·첨부 file ID·에이전트 역할을 SHA-256으로 묶은 불투명 값입니다.
turnId·질문·현재 페이지는 키에 넣지 않으며 세션/사용자/자료/파일 교체/역할이 달라지면
다른 키를 씁니다. 다른 세션의 개인화 문맥을 합치거나 `previous_response_id`를 사용하는
방식이 아니며 Responses의 `store=false`도 유지합니다. 키 원문·자료/질문은 로그에 넣지
않고 `promptCacheLayout`, `promptCacheRouting` boolean만 기존 xAI 계측 로그에 추가합니다.

### 비교·활성화 순서

실제 호출 예산을 정한 후 **동일한 스냅샷을 재생**해 설명·QA·OX를 각각 비교합니다.
자료/file ID·페이지·질문·대화/메모리·문항 수·모델/추론·동시성·스트리밍 여부는 고정하고,
추적용 turnId/traceId만 호출마다 새로 발급합니다. 세션에서 계속 새 질문을 이어 가면
대화 문맥도 변하므로 같은 조건의 비교가 아닙니다.

| 비교군 | layout | routing | 목적 |
| --- | --- | --- | --- |
| A | false | false | 기존 provider 요청과 동일한 기준선 |
| B | true | false | 배치 변경만의 효과 |
| C | false | true | 라우팅 키만의 효과 |
| D | true | true | 두 변경의 결합 효과 |

각 군에서 최초 요청과 반복 요청을 나눠 기록하고, 가능한 한 군의 순서를 교차하여
시간대 차이를 줄입니다. 프로세스 재시작은 provider 캐시를 비우지 않으므로 첫 요청을
cold cache로 단정하지 않습니다. `sum(cachedInputTokens) / sum(inputTokens)`와 단계별
Planner 시간·첫 본문 시간·완료 시간·오류/재시도·답변 근거/범위/퀴즈 판단을 함께 평가합니다.
provider 캐시 상태를 통제할 수 없고 생성도 비결정적이므로 소수 표본을 개선율로 일반화하지
않습니다. 입력 값 보존 테스트는 학습 품질이 동일하다는 실증을 대신하지 않습니다.

dev에서 해당 AI 프로세스의 환경 변수만 변경하고 재기동하여 비교합니다. Compose 환경에서는
`.env`에 적는 것만으로 충분하지 않으며 두 변수가 **ai-service 컨테이너 환경에 전달**되어야
합니다(이 변경에는 Compose/Spring/FE 수정 없음). 문제가 생기면 두 값을 `false`로 되돌려
재기동하면 기존 요청 배치·라우팅으로 복귀합니다. 실측 전에는 지연 단축이나 캐시 적중을
보장하지 않고, 검증된 조합만 후속 배포에서 활성화합니다.

근거: xAI [캐시 작동 원리](https://docs.x.ai/developers/advanced-api-usage/prompt-caching/how-it-works),
[API별 라우팅 설정](https://docs.x.ai/developers/advanced-api-usage/prompt-caching/maximizing-cache-hits).

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
