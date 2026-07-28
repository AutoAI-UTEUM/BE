# Backend ↔ Agent Server 통합 계약 (초안)

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 v0.1 — Spring 협의 전 제안용 |
| 작성일 | 2026-07-23 |
| 범위 | **학습 세션 흐름만** (bridge 9종 보류, 리포트 별도 트랙) |
| LLM | xAI Grok API (기본 모델 `grok-4.5`, OpenAI 호환) |

> 이 문서는 Agent Server(FastAPI) 관점의 제안 초안이다.
> `[결정필요]` 표시는 Spring 협의 후 확정할 항목이다.

---

## 0. 전제와 미결 사항

- Frontend는 Backend만 호출한다. Agent Server는 Backend 내부 전용이다.
- **세션 상태 소유권 `[결정필요]`** — 본 초안은 목표 아키텍처인 **"Backend가 상태 소유, Agent Server는 무상태(stateless)"** 기준으로 작성했다.
  - 요청마다 Backend가 세션 스냅샷을 전달하고, Agent Server는 `statePatch`를 제안만 한다.
  - 대안(과도기): Agent Server가 Redis에 세션 상태를 소유하는 현행 방식. 이 경우 요청 DTO에서 `sessionSnapshot`이 축소되고 Redis env가 추가된다.
- PDF 접근: Gemini fileRef 방식을 폐기하고 **Backend가 추출한 페이지 텍스트를 요청에 동봉**하는 방식을 기본으로 한다(§4.3). 그림/수식 위주 페이지는 이미지 렌더링 전달을 옵션으로 열어둔다.

---

## 1. 공통 규약

### 1.1 인증

- 모든 요청에 `X-AI-SECRET-KEY: <shared secret>` 헤더 필수.
- 실패 시: `401 {"code": "AUTH_ERROR", "message": "Unauthorized agent request."}`
- prod 환경에서 secret 미설정 시 양쪽 모두 기동 실패(fail-fast).

### 1.2 공통 응답 규칙

- 필드는 `camelCase`, 날짜는 ISO 8601(UTC), 페이지 번호는 1-based.
- 빈 목록은 `[]`, 없는 값은 `null`. `null`과 `0`을 섞지 않는다.
- 내부 예외 문자열을 응답에 노출하지 않는다. 에러는 항상 stable code + 고정 메시지.

### 1.3 에러 envelope (비스트리밍)

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청 형식이 올바르지 않습니다.",
  "details": { "field": "event.type", "reason": "unknown event type" }
}
```

- stable error code: `VALIDATION_ERROR | AUTH_ERROR | AI_TIMEOUT | AI_QUOTA | AI_UNAVAILABLE | INTERNAL_ERROR`
- `details`는 항상 **객체**(현행 v3의 배열 방언 폐기).

### 1.4 멱등성

- 모든 turn/grade 요청에 Backend가 발급한 `turnId`(uuid) 포함.
- 같은 `turnId` 재수신 시 Agent Server는 새 LLM 호출 없이 `error(code=DUPLICATE_TURN)` 또는 캐시 응답을 반환한다. `[결정필요: 재전송 시 replay 지원 여부]`

---

## 2. 엔드포인트 목록

| Method | Path | 응답 | 용도 |
| --- | --- | --- | --- |
| POST | `/internal/ai/turn` | NDJSON 스트림 | 학습 턴 처리 (설명/QA/퀴즈 생성/진단/교정 오케스트레이션) |
| POST | `/internal/ai/grade` | JSON | SHORT/ESSAY 답안 채점 (단건, 비스트리밍) |
| GET | `/internal/ai/health` | JSON | liveness (`{"status":"ok"}`) |

- 그 외 현행 엔드포인트(`/bridge/*`, `/api/v2/*`, `/api/v3/*`)는 **보류(freeze)** — 신규 개발 없음, 차후 폐기.

---

## 3. POST /internal/ai/turn

### 3.1 요청 DTO

```json
{
  "schemaVersion": "1.0",
  "turnId": "uuid",
  "sessionId": 123,
  "materialId": 45,
  "studentId": 6,
  "event": {
    "type": "USER_MESSAGE",
    "payload": { "text": "편차가 뭔지 모르겠어" }
  },
  "sessionSnapshot": {
    "currentPage": 3,
    "pageCount": 42,
    "phase": "EXPLAINED",
    "learnerLevel": "중위권, 수식 전개를 어려워함",
    "conversationSummary": "1~2페이지 설명 완료, 평균 개념 질문 1회",
    "recentMessages": [
      { "role": "ASSISTANT", "content": "...", "pageNumber": 3 }
    ],
    "activeQuiz": null,
    "pendingDiagnosis": null,
    "quizAssessments": [ { "...": "최근 평가 메모 큐 (최대 N개)" } ],
    "learnerMemoryDigest": "약점: 분수 나눗셈 역수 개념 / 선호: 단계별 예시"
  },
  "pageContext": {
    "current": { "pageNumber": 3, "text": "…추출 텍스트…" },
    "prev":    { "pageNumber": 2, "text": "…" },
    "next":    { "pageNumber": 4, "text": "…" },
    "images":  []
  },
  "config": {
    "model": null,
    "locale": "ko"
  }
}
```

- `sessionSnapshot.phase`: `ENTERED | EXPLAINING | EXPLAINED | QUIZ_PENDING | QUIZ_IN_PROGRESS | DIAGNOSIS_PENDING | REPAIRING` `[결정필요: 상태 머신 확정]`
- `pageContext.images[]`: `{ "pageNumber": 3, "mimeType": "image/png", "dataBase64": "..." }` — 그림 위주 페이지 옵션.
- `config.model`: null이면 Agent Server 기본값. 에이전트별 모델 오버라이드는 Agent Server 내부 정책.

### 3.2 이벤트 타입 (event.type)

| 타입 | payload | 설명 |
| --- | --- | --- |
| `SESSION_ENTERED` | `{}` | 세션 입장. 시작 안내 + 설명 여부 위젯 |
| `START_EXPLANATION_DECISION` | `{ "accepted": true }` | "설명 시작할까요?" 응답 |
| `PAGE_CHANGED` | `{ "page": 4 }` | 페이지 이동 후 통지 (이동 자체는 Backend가 반영) |
| `USER_MESSAGE` | `{ "text": "..." }` | 자유 입력 (질문/명령) |
| `QUIZ_DECISION` | `{ "accepted": true }` | "퀴즈 볼까요?" 응답 |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "MCQ", "coverage": {"startPage":1,"endPage":3} }` | 퀴즈 유형 선택 → 퀴즈 생성 |
| `QUIZ_GRADED` | `{ "quizId": "...", "gradingResult": { ... } }` | 채점 완료 통지(MCQ/OX는 Backend 채점, SHORT/ESSAY는 §5 결과). 평가 메모 생성·진단 분기 판단 |
| `DIAGNOSIS_ANSWER_SUBMITTED` | `{ "text": "..." }` | 진단 질문에 대한 학생 답변 → 오개념 교정 |
| `REVIEW_DECISION` / `RETEST_DECISION` | `{ "accepted": true }` | 복습/재시험 여부 응답 |
| `NEXT_PAGE_DECISION` | `{ "accepted": true }` | 다음 페이지 이동 여부 응답 |
| `SESSION_COMPLETE` | `{}` | 세션 종료. 요약/메모리 후보 정리 |

- 퀴즈 제출·채점 자체는 turn에 포함하지 않는다: Backend가 `/api/quizzes/{quizId}/submit` 처리(MCQ/OX 자체 채점, SHORT/ESSAY는 §5 호출) 후 `QUIZ_GRADED` 이벤트로 turn을 호출한다.

### 3.3 NDJSON 스트림 이벤트 (단일 방언 — 확정안)

한 줄 = JSON 객체 하나. 이벤트 순서 규칙: `heartbeat`는 언제든, `done` 또는 `error`는 **정확히 1회, 항상 마지막 줄**.

```jsonl
{"type":"thought_delta","agent":"orchestrator","text":"현재 페이지 설명이 필요한 상황..."}
{"type":"answer_delta","agent":"explainer","text":"## 핵심 요지\n분산은..."}
{"type":"ui","widget":"NEXT_PAGE_DECISION"}
{"type":"heartbeat"}
{"type":"done","data":{ ... }}
```

| type | 필드 | 설명 |
| --- | --- | --- |
| `thought_delta` | `agent`, `text` | 합의된 진행 요약(모델 출력 필드). 원시 추론 아님. 저장하지 않음 |
| `answer_delta` | `agent`, `text` | 학생에게 보여줄 본문 청크 (Markdown) |
| `ui` | `widget` 또는 `modal`, `args?` | 위젯: `START_EXPLANATION_DECISION / QUIZ_DECISION / NEXT_PAGE_DECISION / REVIEW_DECISION / RETEST_DECISION`, 모달: `QUIZ_TYPE_PICKER` (+`mode:"RETEST"`) |
| `navigation` | `targetPage`, `reason` | 페이지 이동 제안 (반영 여부는 Backend/FE 판단) |
| `heartbeat` | — | 10초 간격. 소비자는 무시 |
| `done` | `data` | 턴 완료. §3.4 |
| `error` | `code`, `message`, `details{}` | 실패 종료. 이후 어떤 이벤트도 없음 |

- `agent` enum: `orchestrator | explainer | qa | quiz | grader | diagnosis | repair | system`
- 스트림 error code: `TURN_FAILED | PLAN_INVALID | TOOL_EXECUTION_FAILED | QUIZ_GENERATION_FAILED | REPAIR_GENERATION_FAILED | AI_TIMEOUT | AI_QUOTA | AI_UNAVAILABLE`

### 3.4 done.data 구조

```json
{
  "messages": [
    { "role": "ASSISTANT", "agent": "explainer", "content": "…최종 본문…", "pageNumber": 3 }
  ],
  "statePatch": {
    "phase": "EXPLAINED",
    "conversationSummaryAppend": "3페이지 설명 완료",
    "activeQuiz": null,
    "pendingDiagnosis": null
  },
  "uiActions": [ { "widget": "QUIZ_DECISION" } ],
  "quiz": null,
  "quizAssessment": null,
  "diagnosis": null,
  "memoryCandidates": [],
  "usage": { "model": "grok-4.5", "inputTokens": 1234, "outputTokens": 567 }
}
```

- `messages[].content`는 `answer_delta` 누적분과 동일해야 한다(불일치 시 done 기준).
- `statePatch`: Backend가 허용된 전이인지 검증 후 반영. 알 수 없는 키는 거부.
- `quiz`: `QUIZ_TYPE_SELECTED` 턴에서만. §4 퀴즈 스키마.
- `quizAssessment` / `diagnosis`: `QUIZ_GRADED` 턴에서. 진단 질문이 있으면 `diagnosis.diagnosticPrompt`가 messages에도 포함된다.
- `memoryCandidates[]`: 학습자 메모리 **후보**. 즉시 반영 금지 — 반복 패턴 확인 후 Backend가 승격. `{ "kind": "WEAKNESS|STRENGTH|MISCONCEPTION|PREFERENCE", "content": "...", "evidence": "..." }`

---

## 4. 퀴즈 스키마 (done.data.quiz)

```json
{
  "schemaVersion": "1.0",
  "quizId": "uuid",
  "quizType": "MCQ",
  "page": 3,
  "coverage": { "startPage": 1, "endPage": 3 },
  "title": "1~3페이지 학습 점검",
  "questions": [ ... ]
}
```

- `quizType`: `MCQ | OX | SHORT | ESSAY` (README/Exam Studio와 통일. 현행 v3의 `Five_Choice` 계열 명명 폐기)
- 문항 공통: `questionId`(uuid), `questionText`, `maxScore`
- 유형별 필수 필드:
  - `MCQ`: `choices[] { choiceId, text }`, `answerChoiceId`, `explanation`
  - `OX`: `answerValue`(boolean), `explanation`
  - `SHORT`: `referenceAnswer`, `gradingCriteria`
  - `ESSAY`: `modelAnswer`, `rubric[] { criterion, weight }` — **ESSAY는 전용 스키마 사용 (현행의 SHORT 재사용 금지)**
- 정답 필드는 Backend만 보관하고 FE 전달 시 제거한다(Backend 책임).
- 문항 수: 5 기본, 5~10 범위에서 Agent 판단.

## 5. POST /internal/ai/grade

### 5.1 요청

```json
{
  "schemaVersion": "1.0",
  "turnId": "uuid",
  "sessionId": 123,
  "quiz": { "...": "§4 스키마 (SHORT/ESSAY 문항만)" },
  "answers": [
    { "questionId": "q-1", "response": "학생 답안 텍스트" }
  ],
  "pageContext": { "current": { "pageNumber": 3, "text": "…" } },
  "learnerMemoryDigest": "…"
}
```

- **매칭은 반드시 `questionId` 기반** (index 매칭 금지 — 현행 오채점 버그 재발 방지).
- 요청의 모든 `questionId`가 quiz에 존재하지 않으면 `400 VALIDATION_ERROR`.

### 5.2 응답

```json
{
  "quizId": "uuid",
  "items": [
    {
      "questionId": "q-1",
      "score": 0.7,
      "maxScore": 1.0,
      "verdict": "PARTIAL",
      "feedback": "역수 개념은 정확하나 이유 설명이 누락..."
    }
  ],
  "totalScore": 3.4,
  "maxScore": 5.0,
  "scoreRatio": 0.68,
  "passed": true,
  "passScoreRatio": 0.6,
  "gradingSource": "AI"
}
```

- `verdict`: `CORRECT | PARTIAL | WRONG`. 응답 items 수 = 요청 answers 수 (불일치 시 Agent Server가 재시도 후 실패 처리).
- LLM 채점 실패 시: `502 { "code": "AI_UNAVAILABLE", ... }` — 부분 결과를 반환하지 않는다.

---

## 6. Grok 연동 규칙 (Agent Server 내부, 계약 참고사항)

- 모든 에이전트 출력은 `response_format: json_schema`(structured outputs)로 강제 — 플래너 Plan, 퀴즈 JSON, 채점 JSON 모두 스키마 레벨 보장.
- `thought_delta`는 모델의 원시 reasoning이 아니라 **출력 스키마에 정의된 요약 필드**를 스트리밍한 것.
- 페이지 컨텍스트는 텍스트 동봉이 기본(파일 업로드/attachment_search 미사용). 프롬프트 캐싱은 시스템 프롬프트 고정부에서 자동 적용.
- 스트림 타임아웃: 요청당 최대 `AI_TURN_TIMEOUT_SECONDS`(기본 180s). 초과 시 `error(AI_TIMEOUT)` 후 종료.
- LLM 계층은 OpenAI 호환 클라이언트로 추상화(`base_url=https://api.x.ai/v1`) — 프로바이더 교체 대비.

## 7. 환경변수 계약

| 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `XAI_API_KEY` | ✅ | — | Grok API 키 |
| `MODEL_NAME` | — | `grok-4.5` | 기본 모델 |
| `AI_SECRET_KEY` | prod ✅ | — | Backend↔Agent 공유 시크릿 |
| `APP_ENV` | — | `local` | `local/dev/prod` |
| `AI_TURN_TIMEOUT_SECONDS` | — | `180` | 턴 스트림 타임아웃 |
| `AI_HEARTBEAT_INTERVAL` | — | `10` | heartbeat 간격(초) |
| `REDIS_HOST/PORT` | `[결정필요]` | — | 상태 소유권 결정 시 |

## 8. Spring 협의 필요 항목 정리

- [ ] 세션 상태 소유권 (stateless+snapshot vs Agent Redis 소유) — §0
- [ ] `sessionSnapshot` 필드 확정 (특히 phase 상태 머신, quizAssessments 큐 길이)
- [ ] `statePatch` 허용 키 목록과 검증 규칙
- [ ] turnId 중복 시 동작 (거부 vs replay)
- [ ] 퀴즈 정답 필드의 FE 노출 차단 책임 (Backend 확정)
- [ ] pageContext 텍스트 추출 책임과 품질 (Backend PDF 파서 선정), 이미지 옵션 사용 여부
- [ ] NDJSON→SSE 변환 규약 (Backend가 FE로 흘릴 때 이벤트명 매핑)
