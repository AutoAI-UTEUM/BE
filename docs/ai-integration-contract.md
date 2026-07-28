# ai-integration-contract v0.4 — Spring ↔ AI Service 통합 계약

| 항목 | 내용 |
| --- | --- |
| 상태 | v0.4 — #27 서면 확정 반영 |
| 작성일 | 2026-07-23 |
| 역할 | Epic5 ⓐ(#27 턴 계약)·Epic6 ⓐ(퀴즈 계약)·Epic7 ⓐ(평가·진단·메모리 계약)의 AI측 상위 기준 문서 |
| 선행 결정 | DEC-002 v2(모델), DEC-006(추출 책임), DEC-013(SSE 기본, 세부 잔여), DEC-014(X-Internal-Token), DEC-022(하이브리드) |

> v0.2 → v0.3 주요 변경: 이벤트명·스트림 이벤트를 팀 표준으로 개명, 내부 엔드포인트를 api-spec §10의 5종 체계로 재편, statePatch 허용목록을 api-spec 표와 통일, 오류를 category 5종 체계로 교체, RuleRouter 개념을 DEC-022 하이브리드(규칙은 Spring, 판단은 FastAPI Orchestrator)로 재배치.

---

## 0. 아키텍처 전제 (확정 사항 반영)

- **하이브리드 원칙 (DEC-022)**
  - 자유 턴(설명·질문·퀴즈 유형 선택·진단 답변·교정 후 질문): Spring은 에이전트를 판단하지 않고 `/internal/ai/turn` 단일 진입점으로 전달. 에이전트 선택은 FastAPI Orchestrator 책임. 교정(RepairAgent)·메모리 후보/승격은 turn 내부 도구.
  - 결정적 파이프라인(퀴즈 제출 후): Spring이 규칙(이벤트 타입 + 점수 기준)으로 `grade → quiz-assessment → [미달 시] diagnosis`를 순차 호출. LLM 판단 없음.
  - v0.2의 "RuleRouter" 개념은 이 구조로 흡수됨 — **결정 가능한 분기는 전부 Spring 측 규칙**(StateReducer·파이프라인 트리거·페이지 이동), FastAPI 내부에는 별도 규칙 라우터를 두지 않는다. Policy/Verifier는 LLM Plan의 스키마·허용 도구·교수 정책 검증만 담당한다(동일 판단 로직 이중화 금지).
- **상태 소유: Spring** — FastAPI는 무상태. 요청마다 스냅샷을 받고 statePatch를 제안하며, Spring이 허용목록으로 검증 후 반영. FastAPI는 자체 영속 저장소(Redis 포함)를 두지 않는다.
- **PDF 접근** — 자료 업로드 시 `/internal/ai/extract`로 1회 추출 → Spring이 `material_pages`에 저장 → 턴마다 현재±1 페이지 텍스트를 스냅샷에 동봉. fileRef/Files API 미사용.

## 1. 공통 규칙

- 인증: 모든 내부 호출에 `X-Internal-Token` 헤더 (DEC-014, env `EDUPILOT_INTERNAL_TOKEN`). 불일치 시 category `AUTH`.
- 필드 camelCase, 시간 ISO-8601 UTC, `schemaVersion: "1.0"` 전 요청/응답 포함.
- 내부 오류 응답 형식 (api-spec §10):

```json
{ "schemaVersion": "1.0", "error": { "code": "EXTRACTION_FAILED", "category": "INTERNAL", "message": "운영 노출 가능한 요약", "retryable": false }, "traceId": "01J..." }
```

| category | Spring 매핑 | 예 |
| --- | --- | --- |
| AUTH | 500 (외부 비노출) | 토큰 불일치 |
| TIMEOUT | AI_SERVICE_TIMEOUT 504 | LLM 시간 초과 |
| SCHEMA | AI_RESPONSE_INVALID 502 | 구조화 출력 재시도 후 실패 |
| POLICY | AI_POLICY_REJECTED 409/502 | Plan 정책 위반 |
| INTERNAL | AI_SERVICE_UNAVAILABLE 503 / 502 | 추출 실패, 내부 오류 |

- 원시 예외 문자열·프롬프트·내부 추론을 message에 넣지 않는다.

## 2. 내부 엔드포인트 (5종 — api-spec §10 확정 체계)

| Method | URL | 목적 | 호출 시점 |
| --- | --- | --- | --- |
| POST | `/internal/ai/extract` | PDF 페이지 텍스트 추출 (결정적 전처리) | 자료 업로드 후 비동기 |
| POST | `/internal/ai/turn` | 자유 턴 (설명·QA·퀴즈 생성·교정·메모리 도구 포함) | turns 이벤트 수신 시 |
| POST | `/internal/ai/grade` | SHORT/ESSAY 채점 | 제출 파이프라인 1단계 |
| POST | `/internal/ai/quiz-assessment` | 내부 평가 생성 | 파이프라인 2단계 (채점 후 항상) |
| POST | `/internal/ai/diagnosis` | 진단 질문 생성 | 파이프라인 3단계 (60% 미달 시 — DEC-010) |

- repair·memory 전용 엔드포인트는 두지 않는다 (turn 내부 도구 — api-spec §10 확정).
- `GET /health` (+readiness 대상은 Epic8 ⓐ 계약에 따름).

## 3. POST /internal/ai/turn

### 3.1 요청 (api-spec §10 최소 구조 기준)

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-123",
  "session": { "sessionId": 100, "userId": 1, "materialId": 10, "currentPage": 3, "pageStatus": "NOT_EXPLAINED" },
  "event": { "eventType": "USER_QUESTION", "payload": { "message": "편차가 뭔지 모르겠어" } },
  "context": {
    "currentPageText": "...", "previousPageText": "...", "nextPageText": "...",
    "recentMessages": [], "qaThreadDigest": null,
    "quizAssessments": [], "learnerMemoryDigest": null,
    "learnerLevel": null, "learnerConfidence": null,
    "pendingDiagnosis": null, "latestRepair": null,
    "memory": { "temporaryCandidates": [] }
  }
}
```

- `quizAssessments`: 세션 스코프 최근 5개 (DEC-011). 승격 판단용 교차 조회는 Spring 별도 경로.
- `learnerLevel`/`learnerConfidence`: Spring이 learner_memories·최근 평가에서 파생. null이면 기본 수준 동작.
- `latestRepair`: 직전 교정 답변 원문 포함 — 교정 후 USER_QUESTION에서 QaAgent가 문맥 승계.

### 3.2 이벤트 타입 (자유 턴 4종)

| eventType | payload | Orchestrator 기대 동작 |
| --- | --- | --- |
| `EXPLAIN_CURRENT_PAGE` | `{ "detailLevel": "NORMAL\|DETAILED" }` | ExplainerAgent — 현재 페이지 중심 설명 |
| `USER_QUESTION` | `{ "message": "..." }` | QaAgent — START_NEW/FOLLOW_UP 판단, latestRepair 있으면 교정 문맥 승계 |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "MCQ\|OX\|SHORT\|ESSAY" }` | QuizAgent (GENERATE_QUIZ_* 도구) |
| `DIAGNOSIS_ANSWER_SUBMITTED` | `{ "diagnosisId": 30, "answer": "..." }` | RepairAgent — 진단 답변 기반 짧은 교정 |

- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED`는 **삭제 확정** — 교정 후 질문은 `USER_QUESTION` 재사용 + `latestRepair` 문맥 승계로 커버 (Epic5 ⓐ 결정). api-spec §5 표에서 제거할 것.

### 3.3 응답

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-123",
  "turnGoal": "ANSWER_USER_QUESTION",
  "actionsExecuted": [
    {
      "actionId": "action-1",
      "agent": "QaAgent",
      "status": "SUCCESS",
      "adjustments": [
        {
          "field": "page",
          "from": 2,
          "to": 3,
          "reason": "PAGE_MISMATCH_CORRECTED"
        }
      ]
    }
  ],
  "messages": [ { "messageType": "QA", "content": "..." } ],
  "statePatch": {},
  "uiActions": [],
  "memoryCandidates": [],
  "memoryWrite": null,
  "usage": { "model": "grok-4.5-<date>", "inputTokens": 0, "outputTokens": 0, "reasoningTokens": 0 }
}
```

- `messageType`: `EXPLANATION | QA | DIAGNOSIS | REPAIR | SYSTEM` `[Epic5 ⓐ에서 확정]`
- `actionsExecuted[].adjustments`: Policy가 Plan을 보정한 경우에만 포함하는 선택
  필드입니다. 각 항목은 `{field, from, to, reason}`이며 보정이 없으면 필드를
  생략합니다. `reason`은 자유 문자열로 Spring이 enum 검증 없이 저장합니다.
  초기 reason 값은 `PAGE_MISMATCH_CORRECTED`,
  `EVENT_PAYLOAD_MISMATCH_CORRECTED`입니다.
- `usage`: **채택 확정** — 모든 내부 응답의 표준 선택 필드 (reasoningTokens 포함, 미제공 시 null). Spring은 로그로만 수집(DB 저장 없음) — DEC-002 비용 트리거(월 $150) 판단 데이터.
- 퀴즈 생성 결과는 `actionsExecuted[].artifacts.quizGeneration`에 둡니다. Spring은 이를 검증·저장한 뒤 자체 발급한 quiz ID만 `state.activeQuizId`로 반환합니다.
- `memoryWrite`는 최상위 nullable 필드입니다. Spring은 턴 핵심 저장 커밋 후 별도 트랜잭션에서 반복 근거·confidence 정책을 검증해 승격합니다.
- `uiActions`: 예약 필드이며 AI Service는 항상 `[]`을 반환합니다. Spring은
  비어 있지 않은 값이 오면 무시하고 경고 로그를 남깁니다. 사용자 위젯은
  Spring이 [API 명세](api-spec.md) §5 규칙표에 따라 생성합니다.

### 3.4 statePatch 허용목록 (api-spec §10 표와 동일 — Spring이 이외 전부 거부)

| 필드 | 허용 값 | 비고 |
| --- | --- | --- |
| `pageStatus` | `EXPLAINING, EXPLAINED, QUIZ_READY, DIAGNOSIS_PENDING, REPAIR_COMPLETED` | `NOT_EXPLAINED` 역전이는 페이지 이동(StateReducer)만 |
| `activeQuizId` | 퀴즈 ID 또는 null | 생성 턴에서 설정, 제출 완료 시 Spring 해제 |
| `pendingDiagnosis` | 진단 참조 또는 null | 해제는 교정 완료 턴에서만 |
| `qaThread` | `START_NEW`: `{ "mode": "START_NEW" }`; `FOLLOW_UP`: `{ "mode": "FOLLOW_UP", "threadRef": "qa-{id}" }` | `threadRef`는 Spring이 `qa-{id}` 형식으로 발급. START_NEW에는 포함하지 않고, FOLLOW_UP은 스냅샷 `qaThreadDigest.threadRef`를 그대로 반환 |

- 세션 `status` 전이는 statePatch 불허 (외부 API 전용).

### 3.5 Policy 보정·거부 규칙

Policy/Verifier는 Plan을 다음 범위에서만 결정적으로 보정합니다.

| 구분 | 규칙 |
| --- | --- |
| 보정 허용 | `page`가 스냅샷과 다르면 `currentPage`로 교정하고 adjustment 기록 |
| 보정 허용 | `detailLevel`이 이벤트 payload와 다르면 payload 값으로 교정하고 adjustment 기록 |
| 보정 허용 | 도구별 허용 args 외의 여분 키는 통지 없이 제거 |
| 반드시 거부 | 이벤트와 도구의 불일치 |
| 반드시 거부 | 결정적 파이프라인 전용 도구 사용 |
| 반드시 거부 | `FOLLOW_UP`인데 `qaThreadDigest`가 없음 |
| 반드시 거부 | Plan의 `threadRef`가 스냅샷 값과 다른 위조 |

보정은 허용된 입력을 계약값으로 정규화하는 것에 한정하며, 거부 대상의 의미를
바꾸어 실행하는 용도로 사용하지 않습니다.

### 3.6 턴 내부 파이프라인 (FastAPI)

```
스냅샷 → ContextBuilder → Orchestrator(Grok structured output, Plan)
      → Policy/Verifier(스키마·허용 도구·교수 정책·승격 검증)
      → ToolDispatcher(순차 실행·부분 실패 명시·statePatch 병합) → 표준 응답/스트림
```

- 허용 도구 목록·Plan 검증 기준은 agent-system-spec §6~§7 기준으로 Epic5 ⓐ에서 확정.
- 파이프라인 전용 도구(grade/assessment/diagnosis)가 Plan에 나오면 Policy가 거부.
- Plan 스키마 실패 시 1회 재생성 fallback 후 category `SCHEMA`.
- Policy 강제 규칙 (Epic7): 진단 답변 전 Repair 금지, 메모리 승격 = 독립 근거 ≥2회 + confidence ≥0.7 (DEC-012), 근거 없는 성격·능력 추론 금지.

## 4. 멱등성 · 타임아웃 · 재시도 (v0.4 확정 — DEC-002 §5 이관분)

- **멱등성 책임 분리**: 멱등의 원천은 Spring의 `requestId`(UK(session_id, request_id)) — FastAPI는 무상태이므로 자체 멱등 저장을 하지 않는다. `turnId`는 Spring이 턴마다 새로 발급하는 추적 ID이며, **재시도 시에도 새 turnId 발급**(같은 requestId → Spring이 저장 단계에서 중복 차단). 이로써 v2 패널 지적(재시도 vs DUPLICATE_TURN 충돌)은 구조적으로 해소.
- **타임아웃 (확정 — env로 관리, extract는 #5 실측 후 조정 여지)**:

| 엔드포인트 | 제안 timeout | 근거 |
| --- | --- | --- |
| turn (스트리밍) | 180s (첫 이벤트 30s) | 설명·퀴즈 생성 상한 |
| grade | 90s | high effort + ESSAY 다문항, 비스트리밍 |
| quiz-assessment / diagnosis | 45s | 구조화 출력 단건 |
| extract | 120s | 45MB·300p 상한 (실측 후 조정 — #5 체크리스트) |

- **재시도**: provider 어댑터는 자동 재시도하지 않습니다. SCHEMA는
  Orchestrator의 1회 재생성으로 재시도 예산을 소진합니다. Spring은
  `retryable=true`인 오류만 최대 1회 재시도하며, `retryable=false`는
  재시도하지 않습니다. 부분 결과 반환은 금지합니다(전부 아니면 실패).
- **turn 총 시간**: Plan·Agent 호출을 모두 포함해 180초 이내입니다. 호출별
  남은 시간 예산 분배는 스트리밍 이슈 #25에서 구현합니다.

## 5. 스트림 이벤트와 내부 NDJSON 전송 (#25 확정)

### 5.1 FastAPI → Spring 내부 전송

`POST /internal/ai/turn`은 요청의 `Accept` 헤더로 응답 방식을 선택합니다.

| 요청 `Accept` | 응답 |
| --- | --- |
| `application/x-ndjson` 포함 | HTTP 200 `application/x-ndjson`, 한 줄에 JSON 이벤트 1개 |
| 미지정 또는 그 외 | 기존 §3.3 `TurnResponse` JSON — Spring #24 비스트리밍 경로 유지 |

내부 NDJSON 이벤트는 다음 6종입니다.

| type | 필드 | 설명 |
| --- | --- | --- |
| `status` | `stage` | `PLANNING`, `EXPLAINING`, `ANSWERING`, `FINALIZING` |
| `thought_summary` | `text` | 파이프라인이 만드는 결정적 한국어 진행 문구. 모델 원시 추론이 아니며 저장하지 않음 |
| `content_delta` | `text` | 학습자에게 보여 줄 Markdown 본문 청크 |
| `heartbeat` | 없음 | 10초 동안 다른 이벤트가 없을 때 연결 유지용으로 발행 |
| `completed` | `result` | 최종 `TurnResponse` 전체 — 정확히 1회, 마지막 |
| `error` | `code, category, message, retryable` | 실패 종료 — `completed`와 상호 배타이며 정확히 1회, 마지막 |

- 첫 이벤트는 `status`를 포함해
  `TURN_FIRST_EVENT_TIMEOUT_SECONDS`(기본 30초) 안에 발행합니다.
- `content_delta` 누적은
  `completed.result.messages[].content`의 순서대로 이은 문자열과 정확히
  일치해야 합니다.
- 중단·오류 시 일부 `content_delta`는 미확정이며 `error` 뒤 스트림을
  종료합니다. 부분 메시지나 statePatch를 확정 결과로 반환하지 않습니다.
- AI Service는 `ui_action` 내부 이벤트를 발행하지 않습니다. §3.3의
  `uiActions=[]` 원칙대로 사용자 위젯은 Spring이 생성합니다.

### 5.2 LLM 호출과 시간 예산

- Orchestrator Plan은 기존 `response_format=json_schema` 비스트리밍 호출을
  유지합니다.
- ExplainerAgent·QaAgent만 스트리밍 모드에서 `response_format` 없이 순수
  Markdown을 요청하고 xAI Chat Completions SSE(`stream=true`)의 본문 delta를
  `content_delta`로 변환합니다.
- 스트리밍 모드에서는 모델에 `thoughtSummary`를 요구하지 않습니다.
  `thought_summary` 이벤트는 `PLANNING`·페이지 설명·질문 답변 단계에 맞춰
  파이프라인이 고정 문구로 만듭니다.
- 퀴즈·교정 스텁은 LLM 본문 스트림을 사용하지 않고 `completed` 또는
  `error`로 종료합니다.
- turn 시작 시각에 `TURN_TIMEOUT_SECONDS`(기본 180초) deadline을 한 번
  만들고, Plan 재생성과 Agent를 포함한 매 LLM 호출에 남은 시간만 timeout으로
  전달합니다. 남은 시간이 0 이하이면 provider를 호출하지 않고 즉시
  `TIMEOUT` error로 종료합니다.

### 5.3 Spring → FE 외부 SSE 매핑

- Spring은 내부 NDJSON의 `status`, `thought_summary`, `content_delta`,
  `completed`, `error`를 같은 이름의 외부 SSE 이벤트로 변환합니다.
- 내부 `{"type":"heartbeat"}`는 외부 SSE comment 라인으로 변환하며 FE는
  무시합니다.
- **외부 `ui\_action` (확정)**: 외부 SSE에는 내부에 없는 `ui_action` 이벤트가
  추가됩니다(api-spec §9, DEC-013 — 외부 어휘 6종 유지). AI Service는 발행하지
  않으며, Spring이 내부 `completed`를 검증·저장한 뒤 api-spec §5의 위젯 규칙
  (W1~W7)으로 생성합니다. 발행 순서는 **[위젯이 있으면 `ui_action`] →
  `completed` → 스트림 종료**이며, `completed`는 외부에서도 정확히 1회·마지막
  이벤트입니다.
- **외부 `completed.result` (확정)**: 내부 TurnResponse DTO 원문이 아니라
  Spring의 외부 턴 응답(`turnId`, `sessionId`, `messages`, `uiActions`,
  `state`)입니다. 내부 전용 필드(`statePatch`, `actionsExecuted`, `usage`,
  `memoryCandidates` 등)는 외부로 전달하지 않습니다.
- **취소 (확정, MVP)**: 별도 취소 API 없음 — FE fetch abort → Spring 연결
  종료 감지 → FastAPI 상류 요청 취소.
- **재연결 (확정, MVP)**: `Last-Event-ID` 재전송 미지원 — 재연결 시 FE가
  `GET /api/sessions/{id}` + `GET messages`로 재동기화합니다. 중간 청크는
  비확정이라 유실되어도 저장 상태를 손상시키지 않습니다.
- Spring은 `completed.result`를 검증한 뒤 메시지와 상태를 정확히 1회 확정
  저장하며, `error` 또는 연결 중단 시 저장하지 않습니다.

## 6. 파이프라인 엔드포인트 DTO (api-spec §10 기준 + usage 추가)

### 6.1 POST /internal/ai/extract

- 요청: multipart PDF (≤45MB — DEC-016).
- 응답: `{ "schemaVersion": "1.0", "pageCount": 42, "pages": [{ "pageNumber": 1, "text": "..." }] }`
- 오류: `EXTRACTION_FAILED`(손상/암호화/텍스트 없음 — 하위 사유 코드 분류), `PAGE_LIMIT_EXCEEDED`(300p). 저장·상태 전이는 Spring.

### 6.2 POST /internal/ai/grade

- 요청: `quizId, quizType(SHORT|ESSAY), items[]{questionId, question, modelAnswer, rubric[]{criterion, weight}, maxScore}, studentAnswers[]{questionId, answer}, pageContext{coverageStartPage, coverageEndPage, text}, learnerMemoryDigest`
- 응답: `quizId, quizType, score, maxScore, items[]{questionId, score, maxScore, verdict(CORRECT|PARTIAL|WRONG), feedback}` + `usage`
- 규칙: **questionId 기반 매칭**(index 금지), rubric weight 합 검증, 항목별 점수 산출 후 **합산은 코드에서**(DEC-002 D4), 점수 불변식(0≤score≤maxScore) 자체 검증 → Spring 재검증(GRADING_RESULT_INVALID). reasoning_effort=high.

### 6.3 POST /internal/ai/quiz-assessment

- 요청: `quizResult{quizId, quizType, score, maxScore, passed, items[]}, quizItems[], studentAnswers[], pageContext, learnerMemoryDigest`
- 응답 (§4.8): `understandingSummary, strengths[], weaknesses[], suspectedMisconceptions[], recommendedNextDirection, memoryCandidates[]{type, content, confidence}, evidence[]` + `usage`
- 단일 결과 과잉 단정 금지. Spring이 quiz_assessments 전량 저장 (DEC-011).

### 6.4 POST /internal/ai/diagnosis

- 요청: `quizAssessment{}, quizResult{}, wrongItems[]{questionId, question, studentAnswer, modelAnswer, feedback}, pageContext, learnerMemoryDigest`
- 응답 (§4.7): `focusConcepts[], suspectedMisconceptions[], diagnosticPrompt, evidence[], repairHint` + `usage`
- 정답·전체 해설 미제공 원칙. Spring이 Diagnosis(PENDING)·pendingDiagnosis 설정.

## 7. Grok 연동 규칙 (DEC-002 v2 요약 — 구현 구속)

- 전 에이전트 공통 grok-4.5 dated 버전 고정(`MODEL_NAME`), 미발행 시 alias + golden 표류 감지. 매 응답 `model` 필드 대조 assertion.
- `reasoning_effort`: Plan·설명·QA·퀴즈 생성 = low~medium (자유 턴 첫 토큰 p50 5초 예산), 채점·평가·진단 = high.
- 전 출력 `response_format: json_schema` 강제. `AgentLlmProfile { model, reasoningEffort, maxTokens, temperature? }` config 관리.
환경 변수는 다음 이름과 기본값을 계약으로 사용합니다.

| 환경 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `EDUPILOT_INTERNAL_TOKEN` | 없음(필수) | Spring↔AI 내부 인증 |
| `XAI_API_KEY` | 없음(필수) | xAI API 인증 |
| `MODEL_NAME` | `grok-4.5` | 공통 모델명 |
| `TURN_TIMEOUT_SECONDS` | `180` | turn 총 시간 |
| `TURN_FIRST_EVENT_TIMEOUT_SECONDS` | `30` | 스트림 첫 이벤트 |
| `GRADE_TIMEOUT_SECONDS` | `90` | grade |
| `QUIZ_ASSESSMENT_TIMEOUT_SECONDS` | `45` | quiz-assessment |
| `DIAGNOSIS_TIMEOUT_SECONDS` | `45` | diagnosis |
| `EXTRACT_TIMEOUT_SECONDS` | `120` | extract |
| `AGENT_REASONING_EFFORT` | `medium` | 기본 Agent 프로필 |
| `AGENT_MAX_TOKENS` | `16384` | 기본 최대 출력 토큰 |
| `AGENT_TEMPERATURE` | `null` | 선택적 temperature |
| `ORCHESTRATOR_REASONING_EFFORT` | `low` | Orchestrator 프로필 |
| `EXPLAINER_REASONING_EFFORT` | `medium` | ExplainerAgent 프로필 |
| `QA_REASONING_EFFORT` | `medium` | QaAgent 프로필 |

## 8. 확정 로그 및 문서 반영 대기

**v0.4에서 확정된 사항** (근거: DEC-011·012·024 정합 + MVP 단순화):

- **학습자 메모리 스코프**: 수집(후보·quizAssessments 윈도우)=**세션 스코프**, 승격된 장기 메모리(learner_memories)·digest=**사용자×자료(user×material)**. DEC-024(자료당 ACTIVE 세션 1개)로 실사용상 두 스코프가 거의 일치하며, 세션 완료 후 새 세션에서도 개인화 유지가 장기 메모리의 존재 이유. → Epic7 ⓐ 계약에 명문화.
- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED` 삭제 (§3.2) → api-spec §5 갱신 필요.
- 타임아웃 표(§4)·heartbeat 10s·취소=fetch abort·재연결=재동기화 방식(§5) → Epic5 ⓐ에 그대로 반영, DEC-013 잔여 마감.
- usage 필드 표준 채택 (§3.3).

**보류(Deferred)**:

- 평가 리포트 PDF 출력 — MVP 이후. 재개 시 책임 분리안: 리포트 내용 JSON은 AI 서비스, PDF 렌더링은 Spring/FE (AI 서비스에 PDF 생성 책임 없음). 별도 이슈 + DEC 신규 항목으로 등록.

**타 문서 반영 대기 목록**: api-spec §5(이벤트 삭제)·§9(SSE 세부), Epic5 ⓐ·Epic7 ⓐ 이슈 본문에 위 확정값 기입.
