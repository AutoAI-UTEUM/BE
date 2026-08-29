# ai-integration-contract v0.6 — Spring ↔ AI Service 통합 계약

| 항목 | 내용 |
| --- | --- |
| 상태 | v0.6 확정 — #133 승인 반영, AI Service 구현·배포 검증 대기 |
| 작성일 | 2026-08-02 |
| 역할 | Epic5 ⓐ(#27 턴 계약)·Epic6 ⓐ(퀴즈 계약)·Epic7 ⓐ(평가·진단·메모리 계약)의 AI측 상위 기준 문서 |
| 선행 결정 | DEC-002 v2(모델), DEC-006(추출 책임), DEC-013(SSE 기본, 세부 잔여), DEC-014(X-Internal-Token), DEC-022(하이브리드), DEC-035(xAI Files 단계 전환) |

> v0.2 → v0.3 주요 변경: 이벤트명·스트림 이벤트를 팀 표준으로 개명, 내부 엔드포인트를 api-spec §8의 5종 체계로 재편, statePatch 허용목록을 api-spec 표와 통일, 오류를 category 5종 체계로 교체, RuleRouter 개념을 DEC-022 하이브리드(규칙은 Spring, 판단은 FastAPI Orchestrator)로 재배치.
>
> v0.4 → v0.5 주요 변경: `USER_QUESTION`의 선택 `includeCurrentPage`와 페이지 텍스트 3필드의 조건부 null 규칙을 추가하고, 새 대화 마커 이후의 대화 문맥 경계를 확정했다. 내부 `schemaVersion`은 `"1.0"`을 유지한다.
>
> v0.5 → v0.6 주요 변경: grade 요청의 전 필드에 required·nullable을 명시하고 시험이 숫자 `examId`를 `quizId`로 전달하는 규칙, 선택 문맥 없이 채점하는 규칙, 표준 `AI_REQUEST_INVALID` 오류 봉투를 확정했다. 이는 문서상 필수 필드를 선택으로 완화한 것이 아니라 기존에 없던 필드 강제력을 명문화하고 구현을 일치시키는 변경이다.
>
> 2026-08-25 추가 확정: PDF 원본 직접 참조 전환의 Phase 1로, 기존 텍스트 추출을 유지하면서 kill switch가 켜진 환경에서 추출 성공 원본을 xAI Files에 선택적으로 업로드하고 삭제할 수 있는 내부 계약을 추가했다. Phase 3에서는 Spring이 턴 `context.xaiFileId`를 nullable로 전달하고 AI Service가 설명·QA 실행에 원본을 첨부한다. Phase 5에서는 현재 페이지 단일 범위를 유지하는 QuizAgent와 nullable `xaiFileId`를 받는 개요 생성까지 첨부를 확대한다(DEC-035).

---

## 0. 아키텍처 전제 (확정 사항 반영)

- **하이브리드 원칙 (DEC-022)**
  - 자유 턴(설명·질문·퀴즈 유형 선택·진단 답변·교정 후 질문): Spring은 에이전트를 판단하지 않고 `/internal/ai/turn` 단일 진입점으로 전달. 에이전트 선택은 FastAPI Orchestrator 책임. 교정(RepairAgent)·메모리 후보/승격은 turn 내부 도구.
  - 결정적 파이프라인(퀴즈 제출 후): Spring이 규칙(이벤트 타입 + 점수 기준)으로 `grade → quiz-assessment → [미달 시] diagnosis`를 순차 호출. LLM 판단 없음.
  - v0.2의 "RuleRouter" 개념은 이 구조로 흡수됨 — **결정 가능한 분기는 전부 Spring 측 규칙**(StateReducer·파이프라인 트리거·페이지 이동), FastAPI 내부에는 별도 규칙 라우터를 두지 않는다. Policy/Verifier는 LLM Plan의 스키마·허용 도구·교수 정책 검증만 담당한다(동일 판단 로직 이중화 금지).
  - 단, 이벤트로 결과가 유일하게 결정되는 턴의 Plan 합성과 결정적 안내 fast-path(페이지 이동 안내·빈 페이지)는 AI 내부 규칙으로 처리한다(설계 승인, 2026-08-17).
- **상태 소유: Spring** — FastAPI는 무상태. 요청마다 스냅샷을 받고 statePatch를 제안하며, Spring이 허용목록으로 검증 후 반영. FastAPI는 자체 영속 저장소(Redis 포함)를 두지 않는다.
- **PDF 접근** — 자료 업로드 시 `/internal/ai/extract`로 1회 추출 → Spring이 `material_pages`와 nullable xAI file ID를 저장 → `/internal/ai/outline`에는 저장된 전 페이지 텍스트와 nullable `xaiFileId`를 전달하고, 턴마다 현재±1 페이지 텍스트와 nullable `xaiFileId`를 스냅샷에 동봉한다. 추출 텍스트는 범위·구조 앵커이자 file ID 부재 시 폴백으로 계속 유지한다. AI Service는 file ID가 있으면 Explainer·QaAgent·QuizAgent와 개요 생성의 실제 LLM 호출에 원본을 첨부하고, Plan·결정적 안내·Repair·Note에는 첨부하지 않는다. 첨부 호출도 현재 페이지 텍스트·질문 또는 개요 pages의 범위를 벗어나지 않는다(DEC-035).

## 1. 공통 규칙

- 인증: 모든 내부 호출에 `X-Internal-Token` 헤더 (DEC-014, env `EDUPILOT_INTERNAL_TOKEN`). 불일치 시 category `AUTH`.
- 필드 camelCase, 시간 ISO-8601 UTC, `schemaVersion: "1.0"` 전 요청/응답 포함.
- 내부 오류 응답 형식 (api-spec §8):

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

## 2. 내부 엔드포인트 (api-spec §8 확정 체계)

| Method | URL | 목적 | 호출 시점 |
| --- | --- | --- | --- |
| POST | `/internal/ai/extract` | PDF 페이지 텍스트 추출 (결정적 전처리) | 자료 업로드 후 비동기 |
| POST | `/internal/ai/files` | 기존 PDF의 xAI Files 업로드(추출 없음) | bounded backfill worker |
| DELETE | `/internal/ai/files/{fileId}` | xAI Files 원본 삭제 (404 포함 멱등) | 자료 삭제 후 정리 훅 |
| POST | `/internal/ai/outline` | 자료 요약·목차 구조 생성 | 추출 완료 후 비동기 |
| POST | `/internal/ai/captions` | PDF 페이지 이미지의 시각 정보 캡션 생성 | 추출 완료 후 비동기, 최대 10페이지/요청 |
| POST | `/internal/ai/doc-chat` | 자료·퀴즈 복습 문맥 기반 단일 질문 응답 | 외부 doc-chat 요청당 동기 1회 |
| POST | `/internal/ai/conversation-summary` | 기존 요약과 신규 대화를 합친 학습 문맥 요약 | Spring 비동기 요약 작업 |
| POST | `/internal/ai/criteria/suggest` | 강의실 자료 개요 기반 평가 기준 제안 | 강사 자동 생성 요청 후 비동기 |
| POST | `/internal/ai/turn` | 자유 턴 (설명·QA·퀴즈 생성·교정·메모리 도구 포함) | turns 이벤트 수신 시 |
| POST | `/internal/ai/grade` | SHORT/ESSAY 채점 | 제출 파이프라인 1단계 |
| POST | `/internal/ai/quiz-assessment` | 내부 평가 생성 | 파이프라인 2단계 (채점 후 항상) |
| POST | `/internal/ai/diagnosis` | 진단 질문 생성 | 파이프라인 3단계 (60% 미달 시 — DEC-010) |
| POST | `/internal/ai/exams/draft` | 시험 문항 초안 생성 | 강사 요청 시 동기 호출 |

- repair·memory 전용 엔드포인트는 두지 않는다 (turn 내부 도구 — api-spec §8 확정).
- `GET /health` (+readiness 대상은 Epic8 ⓐ 계약에 따름).

## 3. POST /internal/ai/turn

### 3.1 요청 (api-spec §8 최소 구조 기준)

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-123",
  "session": { "sessionId": 100, "userId": 1, "materialId": 10, "currentPage": 3, "pageStatus": "NOT_EXPLAINED" },
  "event": { "eventType": "USER_QUESTION", "payload": { "message": "편차가 뭔지 모르겠어", "includeCurrentPage": true } },
  "context": {
    "xaiFileId": "file-abc123",
    "conversationSummary": null,
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
- `conversationSummary`: 선택 nullable 필드이며 이전 대화의 압축 보조 문맥이다. Plan과 QaAgent가 사용하되 최근 대화와 모순되면 최근 대화를 우선한다. 필드가 없거나 null이면 기존 동작과 같다.
- `learnerLevel`/`learnerConfidence`: Spring이 learner_memories·최근 평가에서 파생. null이면 기본 수준 동작.
- `latestRepair`: 직전 교정 답변 원문 포함 — 교정 후 USER_QUESTION에서 QaAgent가 문맥 승계.
- `currentPageText`의 타입은 `string | null`이다. null은 `USER_QUESTION`이면서 `includeCurrentPage=false`인 턴에서만 허용한다. `EXPLAIN_CURRENT_PAGE`와 `QUIZ_TYPE_SELECTED`에서는 계속 필수이며, AI Service는 eventType과 context를 교차 검증해 위반 요청을 category `SCHEMA`로 거부한다.
- `QUIZ_TYPE_SELECTED`에서 현재 페이지가 READY 개요의 `quizCheckpoints[].triggerPage`이면 Spring은 기존 nullable `quizContext`에 `{coverage:{startPage,endPage},pages:[{pageNumber,text}]}`를 추가한다. `pages`는 coverage 전 범위를 오름차순으로 정확히 한 번 포함하며 캡션 병합 텍스트의 합계를 앞에서부터 12,000자로 제한한다. 체크포인트가 아니거나 계획이 없으면 `quizContext=null`로 현재 페이지 단일 출제 경로를 유지한다.
- `xaiFileId`의 타입은 `string | null`이다. 구자료·업로드 실패 자료는 `null`이며 외부 API에는 노출하지 않는다. AI Service는 이 값을 Plan 입력이나 turn LLM 호출 로그에 넣지 않고 Explainer·QaAgent·QuizAgent 호출에서 xAI Responses API의 `input_file.file_id`로 사용하며 `store=false`를 강제한다.
- `includeCurrentPage=false`이면 Spring은 `xaiFileId`, `currentPageText`, `previousPageText`, `nextPageText`를 모두 null로 전달하고 그 외 context 필드는 유지한다. 선택 필드인 `conversationSummary`는 페이지 첨부 여부와 독립적으로 전달할 수 있다.
- `includeCurrentPage=false`인데 페이지 텍스트가 전달된 경우 AI Service는 해당 context를 무시하지 않고 사용한다. 이 조합의 정합 책임은 Spring에 있다.
- 방어적으로 `includeCurrentPage=false`인데 `xaiFileId`가 전달돼도 AI Service는 파일을 첨부하지 않는다. 페이지 이동 안내·빈 페이지 고정 안내는 file ID 유무와 무관하게 LLM을 호출하지 않는다.

### 3.2 이벤트 타입 (자유 턴 4종)

| eventType | payload | Orchestrator 기대 동작 |
| --- | --- | --- |
| `EXPLAIN_CURRENT_PAGE` | `{ "detailLevel": "NORMAL\|DETAILED" }` | ExplainerAgent — 현재 페이지 중심 설명 |
| `USER_QUESTION` | `{ "message": "...", "includeCurrentPage": true\|false }` (`includeCurrentPage` 선택, 생략 시 `true`) | Orchestrator가 Plan에서 START_NEW/FOLLOW_UP을 결정하고, QaAgent는 전달받아 수행. latestRepair 있으면 교정 문맥 승계 |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "MCQ\|OX\|SHORT\|ESSAY" }` | QuizAgent (GENERATE_QUIZ_* 도구) |
| `DIAGNOSIS_ANSWER_SUBMITTED` | `{ "diagnosisId": 30, "answer": "..." }` | RepairAgent — 진단 답변 기반 짧은 교정 |
| `NOTE_REQUESTED` | `{}` | NoteAgent — 현재 학습 문맥을 노트 초안으로 작성 |

- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED`는 **삭제 확정** — 교정 후 질문은 `USER_QUESTION` 재사용 + `latestRepair` 문맥 승계로 커버 (Epic5 ⓐ 결정). api-spec §5 표에서 제거할 것.
- `includeCurrentPage=false`이면 QaAgent는 페이지 근거 제약을 완화해 일반 학습 지식으로 답변할 수 있다. 단, 업로드 자료에 실제로 어떤 내용이 있는지 추측하거나 지어내지 않으며 학습과 무관한 요청에는 기존 한계 안내를 적용한다.
- QA thread와 `latestRepair` 문맥 승계는 `includeCurrentPage`의 true/false와 무관하게 유지한다.

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
  "quiz": null,
  "noteDraft": null,
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
- 퀴즈 생성 턴에서는 turn 응답 최상위의 nullable `quiz` 필드에 전체 퀴즈
  JSON(§6.2 생성 스키마, 정답·비공개 필드 포함)을 반환합니다. 그 외 턴에서는
  `null`입니다. Spring이 이를 검증·분리 저장(비공개 필드는 학생 노출 DTO에서
  제거)하고 자체 발급한 quiz ID만 `state.activeQuizId`로 반환합니다. AI는
  statePatch에 `activeQuizId`를 설정하지 않습니다.
- `memoryCandidates[]`: 각 후보는
  `{type, content, confidence, evidence[], promotionRequested}`이며 `type`은
  `STRENGTH | WEAKNESS | MISCONCEPTION | PREFERENCE`, `confidence`는 0~1
  숫자입니다. 호환용 `promotionRequested`는 항상 `false`이며 실제 저장·승격은
  Spring 책임입니다.
- `memoryWrite`는 최상위 nullable 필드입니다. `PROMOTE_MEMORY` 성공 시
  `{"candidateIds": [...]}`를 반환하고 그 외에는 `null`입니다. ID는 요청
  `memory.temporaryCandidates`에 있는 값만 허용합니다([api-spec §8](api-spec.md#8-spring--fastapi-내부-api)).
  #36 확정의 `PROMOTE_MEMORY` args는 `{candidateIds}`이며,
  `BUILD_MEMORY_CANDIDATE`만 `{type, content, confidence, evidence[]}`를 사용합니다.
  Spring은 턴 핵심 저장 커밋 후 별도 트랜잭션에서 반복 근거·confidence 정책을
  재검증해 승격합니다.
- `noteDraft`는 선택 nullable 필드이며 `{ "title": "...", "content": "..." }`
  형식입니다. 존재할 때 `title`은 공백이 아니고 60자 이하여야 하며 `content`는
  공백이 아니어야 합니다. Spring은 초안을 영속 메시지·대화 요약·QA thread digest·
  로그에 넣지 않고 외부 completed 응답으로만 전달합니다.
- `uiActions`: 기본값은 `[]`입니다. 다만 `USER_QUESTION` 턴은 아래 §3.3.1의
  `moveNextPage` 제안을 보낼 수 있습니다. 사용자 위젯의 정본은 항상 Spring이
  [API 명세](api-spec.md) §5 규칙표에 따라 생성합니다.

### 3.3.1 uiActions allowlist (`moveNextPage`, `noteProposal`)

AI Service가 `USER_QUESTION` 턴에서 다음 의미의 항목을 제안할 수 있습니다.

```json
{
  "type": "BINARY_DECISION",
  "content": "다음 페이지로 이동할까요?",
  "yesEvent": "MOVE_NEXT_PAGE",
  "noEvent": "WAIT"
}
```

Spring은 다음 조건을 모두 충족할 때만 이 제안을 수용합니다.

1. 이벤트가 `USER_QUESTION`이다.
2. Spring `UiActionResolver`가 산출한 액션이 없다.
3. AI 항목의 `type`, `yesEvent`, `noEvent`가 위 값과 일치한다.
4. Spring이 `pageCount`로 판정한 현재 페이지가 마지막 페이지가 아니다.

수용 시 AI 객체를 저장하지 않고 `UiAction.moveNextPage()` 정본 하나로 치환해
저장·응답합니다. AI의 `content` 문구는 판정에 사용하지 않습니다. 마지막
페이지의 동일 제안은 드롭하고 `last page` 사유로 경고하며, 수용되지 않은
나머지 AI `uiActions`는 기존과 같이 무시하고 경고합니다. 이 allowlist는 외부
wire 스키마를 변경하지 않습니다.

AI Service는 `USER_QUESTION` 턴에서 아래 노트 제안도 보낼 수 있습니다.

```json
{
  "type": "BINARY_DECISION",
  "content": "지금까지 학습한 내용을 노트로 정리할까요?",
  "yesEvent": "NOTE_REQUESTED",
  "noEvent": "WAIT"
}
```

Spring resolver 결과가 비어 있고 세 필드 `type/yesEvent/noEvent`가 정확히 일치할 때만
허용합니다. 저장·외부 응답에는 AI 객체 원문 대신 Spring `UiAction` 정본을 사용하되,
사용자에게 표시하는 `content` 문구는 AI 값을 유지합니다. 그 밖의 AI uiAction은 기존처럼
제거하고 경고합니다.

### 3.4 statePatch 허용목록 (api-spec §8 표와 동일 — Spring이 이외 전부 거부)

| 필드 | 허용 값 | 비고 |
| --- | --- | --- |
| `pageStatus` | `EXPLAINING, EXPLAINED, QUIZ_READY, DIAGNOSIS_PENDING, REPAIR_COMPLETED` | `NOT_EXPLAINED` 역전이는 페이지 이동(StateReducer)만 |
| `activeQuizId` | 퀴즈 ID 또는 null | Spring이 발급·설정, 제출 완료 시 Spring 해제 — AI는 미설정 |
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
| 반드시 거부 | primary action 없이 `BUILD_MEMORY_CANDIDATE`/`PROMOTE_MEMORY`만 있는 Plan |
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
- Policy 강제 규칙 (Epic7): 진단 답변 전 Repair 금지, 메모리 승격 =
  중복 없는 evidence reference 문자열 2개 이상 + 0~1 숫자 confidence ≥0.7
  (DEC-012), 근거 없는 성격·능력 추론 금지.

## 4. 멱등성 · 타임아웃 · 재시도 (v0.4 확정 — DEC-002 §5 이관분)

- **멱등성 책임 분리**: 멱등의 원천은 Spring의 `requestId`(UK(session_id, request_id)) — FastAPI는 무상태이므로 자체 멱등 저장을 하지 않는다. `turnId`는 Spring이 턴마다 새로 발급하는 추적 ID이며, **재시도 시에도 새 turnId 발급**(같은 requestId → Spring이 저장 단계에서 중복 차단). 이로써 v2 패널 지적(재시도 vs DUPLICATE_TURN 충돌)은 구조적으로 해소.
- **타임아웃 (확정 — env로 관리, extract는 #5 실측 후 조정 여지)**:

| 엔드포인트 | 제안 timeout | 근거 |
| --- | --- | --- |
| turn (스트리밍) | 180s (첫 이벤트 30s) | 설명·퀴즈 생성 상한 |
| grade | 90s | high effort + ESSAY 다문항, 비스트리밍 |
| quiz-assessment / diagnosis | 45s | 구조화 출력 단건 |
| extract | 200s | 45MB·300p 추출 + xAI Files 업로드 최대 60초 여유 |

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
- `NOTE_REQUESTED` 턴의 `noteDraft`는 `content_delta`로 보내지 않고 최종
  `completed.result.noteDraft`에만 포함합니다.
- turn 시작 시각에 `TURN_TIMEOUT_SECONDS`(기본 180초) deadline을 한 번
  만들고, Plan 재생성과 Agent를 포함한 매 LLM 호출에 남은 시간만 timeout으로
  전달합니다. 남은 시간이 0 이하이면 provider를 호출하지 않고 즉시
  `TIMEOUT` error로 종료합니다.

### 5.3 Spring → FE 외부 SSE 매핑

- Spring은 내부 NDJSON의 `status`, `thought_summary`, `content_delta`,
  `completed`, `error`를 같은 이름의 외부 SSE 이벤트로 변환합니다.
- 내부 `{"type":"heartbeat"}`는 외부 SSE comment 라인으로 변환하며 FE는
  무시합니다.
- **외부 `ui_action` (확정)**: 외부 SSE에는 내부에 없는 `ui_action` 이벤트가
  추가됩니다(api-spec §9, DEC-013 — 외부 어휘 6종 유지). AI Service는 발행하지
  않으며, Spring이 내부 `completed`를 검증·저장한 뒤 api-spec §5의 위젯 규칙
  (W1~W7)으로 생성합니다. 발행 순서는 **[위젯이 있으면 `ui_action`] →
  `completed` → 스트림 종료**이며, `completed`는 외부에서도 정확히 1회·마지막
  이벤트입니다.
- **외부 `completed.result` (확정)**: 내부 TurnResponse DTO 원문이 아니라
  Spring의 외부 턴 응답(`turnId`, `sessionId`, `messages`, `uiActions`,
  `state`, 선택 `noteDraft`)입니다. 내부 전용 필드(`statePatch`, `actionsExecuted`, `usage`,
  `memoryCandidates` 등)는 외부로 전달하지 않습니다.
- **취소 (확정, MVP)**: 별도 취소 API 없음 — FE fetch abort → Spring 연결
  종료 감지 → FastAPI 상류 요청 취소.
- **재연결 (확정, MVP)**: `Last-Event-ID` 재전송 미지원 — 재연결 시 FE가
  `GET /api/sessions/{id}` + `GET messages`로 재동기화합니다. 중간 청크는
  비확정이라 유실되어도 저장 상태를 손상시키지 않습니다.
- Spring은 `completed.result`를 검증한 뒤 메시지와 상태를 정확히 1회 확정
  저장하며, `error` 또는 연결 중단 시 저장하지 않습니다.

## 6. 파이프라인 엔드포인트 DTO (api-spec §8 기준 + usage 추가)

### 6.1 POST /internal/ai/extract

- 요청: multipart PDF (≤45MB — DEC-016).
- 응답: `{ "schemaVersion": "1.0", "pageCount": 42, "pages": [{ "pageNumber": 1, "text": "..." }], "xaiFileId": "file-...", "warnings": [] }`. `xaiFileId`는 nullable이며 `warnings[]` 항목은 `{type,message}` 형식입니다.
- `EDUPILOT_XAI_FILES_ENABLED=true`일 때만 추출 성공 후 원본 PDF를 xAI Files에 업로드합니다. 기본값은 `false`입니다. 업로드 실패 또는 xAI 제한인 48MiB 초과 시 `xaiFileId=null`, `warnings=[{"type":"FILE_UPLOAD_FAILED","message":"..."}]`로 반환하되 페이지 추출 응답은 HTTP 200을 유지합니다.
- 파일 정리: `DELETE /internal/ai/files/{fileId}`. Spring은 자료 수명 종료 또는 file ID 교체 시 커밋 후 호출하며, 204 외 실패는 로그만 남기고 자료 상태를 되돌리지 않습니다.
- 오류: `EXTRACTION_FAILED`(손상/암호화/텍스트 없음 — 하위 사유 코드 분류), `PAGE_LIMIT_EXCEEDED`(300p). 저장·상태 전이는 Spring.

#### POST /internal/ai/files

- 기존 ACTIVE·READY 자료의 소급 업로드를 위한 upload-only API입니다. multipart PDF(≤48MiB)를 받아 텍스트 추출이나 자료 상태 변경 없이 `{ "schemaVersion":"1.0", "xaiFileId":"file-..." }`를 반환합니다.
- 명시적 내부 호출이므로 `/extract` 자동 업로드 kill switch와 독립적으로 동작합니다. 대량 작업 제어의 정본은 Spring의 기본 OFF `EDUPILOT_XAI_FILE_BACKFILL_ENABLED`입니다.
- 비PDF·빈 파일·매직 불일치는 400 `UNSUPPORTED_FORMAT`, 48MiB 초과는 413 `FILE_TOO_LARGE`, provider 실패는 502 `FILE_UPLOAD_FAILED` 표준 봉투입니다. provider timeout·429·5xx는 retryable이며 영구 4xx·응답 스키마 실패는 retryable=false입니다.
- Spring은 ACTIVE+READY+xaiFileId null 후보를 작은 batch로 claim한 뒤 트랜잭션 밖에서 호출하고, 저장 직전 row lock으로 상태와 ID-null을 재검증합니다. 실패해도 READY를 유지하고 재시도 backoff를 적용하며, 경합으로 저장하지 못한 새 ID는 베스트에포트로 삭제합니다.

#### DELETE /internal/ai/files/{fileId}

- xAI Files의 원본을 정리합니다. 기능 kill switch가 꺼진 상태에서도 기존 파일 정리를 위해 동작합니다.
- 삭제 성공과 xAI 404(이미 없음)는 모두 body 없는 HTTP 204입니다. 그 외 xAI 오류는 HTTP 502, `FILE_DELETE_FAILED`, category `INTERNAL`, `retryable=true` 표준 봉투로 반환합니다.

### 6.2 POST /internal/ai/grade

- QuizAgent 생성 결과는 내부 turn 응답의 `quiz` 전체 JSON으로 반환합니다.
  Spring은 공개/비공개 필드를 분리 저장하고 자체 발급한 `activeQuizId`만 외부
  상태에 반영합니다. AI Service는 `activeQuizId` statePatch를 만들지 않습니다.
- 퀴즈 공통 스키마: `generationId`, `quizType(MCQ|OX|SHORT|ESSAY)`,
  `coverage{startPage,endPage}`, `title`, `questionCount(5~10)`,
  `questions[]`. 문항 공통 공개 필드는 `questionId`, `questionText`,
  `points`입니다. `questionCount`는 `questions` 길이와 같아야 합니다.
- 유형별 필드: MCQ는 공개 `choices[]{choiceId,text}`와 비공개
  `answerChoiceId`·`explanation`, OX는 비공개 `answerValue`·`explanation`,
  SHORT는 비공개 `referenceAnswer`·`gradingCriteria`, ESSAY는 비공개
  `modelAnswer`·`rubric[]{criterion,weight}`를 사용하며 rubric weight 합은
  1이어야 합니다.
- MVP `coverage`는 turn snapshot에 실제 제공된 페이지로 제한합니다. 누적 범위
  퀴즈는 후속 이슈에서 별도 설계합니다.
- `generationId`는 AI Service가 생성하는 추적용 ID이며 멱등 키가 아닙니다.
  멱등성의 원천은 Spring의 `requestId`입니다.
- grade 요청 필드의 강제력은 다음과 같습니다.

| 필드 | 타입 | required | nullable | 규칙 |
| --- | --- | :---: | :---: | --- |
| `schemaVersion` | string | Y | N | 내부 스키마 버전 `"1.0"` |
| `quizId` | integer | Y | N | 통합 퀴즈는 숫자 quiz ID, 별도 시험은 숫자 exam ID. wire 타입 변경은 v0.7 이상에서 별도 계약 |
| `quizType` | `SHORT \| ESSAY` | Y | N | 한 요청에는 한 유형만 포함 |
| `items` | array | Y | N | 실제 호출 시 비어 있지 않음. `questionId`, `question`, `modelAnswer`, `rubric[]`, `maxScore` 포함 |
| `studentAnswers` | array | Y | N | 실제 호출 시 비어 있지 않음. 응답이 있는 대상 문항만 포함 |
| `pageContext` | object | N | Y | 생략 또는 null 허용. 값이 있으면 `coverageStartPage`, `coverageEndPage`, `text` 포함 |
| `learnerMemoryDigest` | string | N | Y | 생략 또는 null 허용 |

`items[]`와 `studentAnswers[]`의 하위 필드 강제력:

| 경로 | 타입 | required | nullable | 규칙 |
| --- | --- | :---: | :---: | --- |
| `items[].questionId` | string | Y | N | 비공백, 응답 매칭 키 |
| `items[].question` | string | Y | N | 비공백 |
| `items[].modelAnswer` | string | Y | N | 비공백 |
| `items[].rubric` | array | Y | N | 서버 기본값 주입 후 1개 이상, weight 합 1.0 |
| `items[].rubric[].criterion` | string | Y | N | 비공백 |
| `items[].rubric[].weight` | number | Y | N | 0 초과, 전체 합 1.0 |
| `items[].maxScore` | number | Y | N | 0 초과 |
| `studentAnswers[].questionId` | string | Y | N | `items[].questionId`와 일치 |
| `studentAnswers[].answer` | string | Y | N | 응답이 있는 문항만 전송하므로 비공백 |

`pageContext`가 존재할 때 `coverageStartPage`, `coverageEndPage`, `text`는 모두 required·non-null이며 페이지 범위는 양수이고 끝 페이지가 시작 페이지보다 작을 수 없습니다.

- Spring은 응답이 있는 SHORT와 ESSAY를 각각 묶어 유형별 최대 1회 호출합니다. 한 유형 호출이 실패해도 다른 유형은 계속 호출합니다. 응답이 있는 AI 채점 문항이 없다면 grade를 호출하지 않습니다.
- `pageContext`가 없으면 GraderAgent는 `question`, `modelAnswer`, `rubric`만으로 채점합니다. AI Service Pydantic 모델은 `page_context`와 `learner_memory_digest`에 실제 기본값 `None`을 두어 생략 요청을 허용해야 합니다.
- 응답: `quizId, quizType, score, maxScore, items[]{questionId, score, maxScore, verdict(CORRECT|PARTIAL|WRONG), feedback}` + `usage`
- 응답은 기존처럼 `quizId`를 에코하며 요청의 정수 값을 그대로 유지합니다. 별도 에코 필드는 추가하지 않습니다. 요청 로그에도 원문 답안을 제외한 정수 `quizId`를 상관관계 값으로 기록할 수 있습니다.
- 규칙: **questionId 기반 매칭**(index 금지), rubric weight 합 검증, 항목별 점수 산출 후 **합산은 코드에서**(DEC-002 D4), 점수 불변식(0≤score≤maxScore) 자체 검증 → Spring 재검증(GRADING_RESULT_INVALID). verdict는 만점 비율 `CORRECT >= 0.8`, `WRONG <= 0.2`, 그 사이는 `PARTIAL`. reasoning_effort=high.
- 필수 필드 누락·타입 오류 같은 body·DTO 검증 실패는 HTTP 422, `items`와 `studentAnswers`의 `questionId` 집합 불일치 같은 필드 간 의미 검증 실패는 HTTP 400입니다. 두 경우 모두 FastAPI 기본 `{"detail":[...]}` 응답을 노출하지 않고 `{"schemaVersion":"1.0","error":{"code":"AI_REQUEST_INVALID","category":"SCHEMA","message":"...","retryable":false},"traceId":"..."}` 표준 봉투로 반환합니다. Spring의 통합 학습 퀴즈 동기 파이프라인은 기존 오류 변환을 유지합니다. 별도 시험 비동기 worker는 HTTP 상태와 무관하게 이 code를 Spring-AI 계약 결함으로 기록하고, 재시도 없이 제출을 `GRADING_FAILED`로 종결합니다. 이미 커밋된 제출을 보상 삭제하거나 원 POST에 500을 반환하지 않습니다(DEC-032).

### 6.3 POST /internal/ai/quiz-assessment

- 요청: `quizResult{quizId, quizType, score, maxScore, passed, items[]}, quizItems[], studentAnswers[], pageContext, learnerMemoryDigest`
- 기존 string wire 필드의 값 의미만 다음처럼 정규화합니다. MCQ의 `studentAnswers[].answer`와 model answer는 `choiceId: 선택지 텍스트`, OX는 `O (true)` 또는 `X (false)`이며 SHORT·ESSAY는 기존 텍스트를 유지합니다. 저장된 퀴즈 공개·비공개 JSON 구조는 변경하지 않습니다.
- 응답 (§4.8): `understandingSummary, strengths[], weaknesses[], suspectedMisconceptions[], recommendedNextDirection, memoryCandidates[]{type, content, confidence}, evidence[]` + `usage`
- 단일 결과 과잉 단정 금지. Spring이 quiz_assessments 전량 저장 (DEC-011).

### 6.4 POST /internal/ai/diagnosis

- 요청: `quizAssessment{}, quizResult{}, wrongItems[]{questionId, question, studentAnswer, modelAnswer, feedback}, pageContext, learnerMemoryDigest`
- `wrongItems[].studentAnswer`와 `modelAnswer`는 §6.3과 동일한 MCQ 선택지 텍스트·OX 의미 텍스트 정규화를 재사용합니다.
- 응답 (§4.7): `focusConcepts[], suspectedMisconceptions[], diagnosticPrompt, evidence[], repairHint` + `usage`
- 정답·전체 해설 미제공 원칙. Spring이 Diagnosis(PENDING)·pendingDiagnosis 설정.

### 6.5 POST /internal/ai/exams/draft

AI Service의 `models/exam_draft.py`와 `docs/contracts/exam-draft.schema.json`이 wire 계약의 정본입니다. 이 호출은 무상태·동기식이며 Spring과 AI Service 모두 초안을 저장하지 않습니다.

요청:

```json
{
  "schemaVersion": "1.0",
  "examId": 77,
  "pageContexts": [
    { "pageNumber": 1, "text": "선별된 첫 페이지 텍스트" }
  ],
  "questionPlan": [
    { "questionType": "MCQ", "count": 3 },
    { "questionType": "SHORT", "count": 2 }
  ]
}
```

- `examId`는 문자열 변환을 허용하지 않는 양의 strict integer이며 응답에서 같은 정수로 에코합니다.
- `pageContexts`는 1~30개, `pageNumber`는 양수이자 요청 내 고유 값, `text`는 비어 있지 않아야 합니다. Main은 여러 자료를 자료 ID·원본 페이지 순으로 모은 뒤 계약 고유성을 위해 컨텍스트 번호 1..N을 부여합니다.
- `questionPlan`은 1~4개 항목이며 유형은 `MCQ | OX | SHORT | ESSAY`, 유형 중복은 허용하지 않고 `count` 총합은 1~20입니다.

응답:

```json
{
  "schemaVersion": "1.0",
  "examId": 77,
  "questions": [
    {
      "questionType": "OX",
      "sourcePageNumber": 1,
      "questionId": "ox-1",
      "questionText": "설명의 옳고 그름을 판단하세요.",
      "points": 5,
      "answerValue": true,
      "explanation": "자료의 정의와 일치합니다."
    }
  ],
  "usage": {
    "model": "grok-4",
    "inputTokens": 1200,
    "outputTokens": 350,
    "reasoningTokens": null
  }
}
```

- `questions[]`는 `questionType` discriminator로 QuizAgent의 MCQ(`choices`, `answerChoiceId`, `explanation`), OX(`answerValue`, `explanation`), SHORT(`referenceAnswer`, `gradingCriteria`), ESSAY(`modelAnswer`, `rubric`) 스키마를 구분하고 `sourcePageNumber`(nullable)를 추가합니다.
- 문항 유형·개수는 계획과 일치해야 하며 `sourcePageNumber`는 null 또는 요청의 페이지 번호여야 합니다. MCQ 정답은 choices 중 하나이고 ESSAY rubric weight 합은 정확히 1.0입니다.
- AI Service는 구조화 출력 계약 실패 시 한 번 재생성합니다. 최종 실패와 Main의 2차 검증 실패는 `AI_RESPONSE_INVALID`; timeout·인증·내부 오류는 §1의 표준 오류 봉투와 `traceId` 규칙을 그대로 사용합니다.
- Main의 전용 read timeout은 120초입니다. 정답·해설을 포함하므로 외부 API는 소유 강사에게만 노출합니다.

### 6.6 POST /internal/ai/outline

- 요청: `{ "schemaVersion": "1.0", "xaiFileId": "file-...", "totalPages": 2, "pages": [{ "pageNumber": 1, "text": "..." }] }`. `xaiFileId`는 nullable이며 생략도 허용한다.
- Spring은 `material_pages`에 저장된 전 페이지 텍스트와 자료의 nullable xAI file ID를 페이지 순서대로 전달하며 텍스트를 절단하지 않는다. 입력 길이 조절은 AI Service 책임이다. `pages[].pageNumber/text`는 범위·구조 앵커이고 첨부 PDF는 같은 범위의 제목·시각 세부 확인에만 사용한다.
- `sections`는 일반 강의 자료에서 3~6개를 목표로 하고 최대 10개이며, 첫 페이지부터 `totalPages`까지 겹침·공백 없이 오름차순으로 정확히 한 번씩 포함해야 한다. 신규 응답의 `description`은 단원 설명이며 구버전 저장 JSON 호환을 위해 Spring에서는 nullable로 읽는다.
- 응답: `{ "schemaVersion": "1.0", "materialSummary": "...", "sections": [{ "title": "...", "description": "...", "startPage": 1, "endPage": 2, "keywords": ["..."] }], "quizCheckpoints": [{ "triggerPage": 2, "coverage": { "startPage": 1, "endPage": 2 } }], "totalPages": 2 }`.
- `quizCheckpoints`는 1~10개이며 trigger는 coverage 끝 페이지와 같고, 각 범위는 자료 안의 section 경계에 맞춰 오름차순·비중복으로 배치한다. Spring은 수신 응답과 저장 JSON을 다시 검증하며 위반 시 개요 전체를 실패시키지 않고 checkpoint 계획만 absent로 강등한 뒤 위반 유형만 WARN으로 남긴다.
- Spring은 응답을 결정적 마크다운으로 렌더링해 `material_overviews.content`에 저장하고, 원본 구조는 `outline_json`에 저장한다. 실패는 자료 자체 상태를 변경하지 않고 개요만 `FAILED`로 전이한다. 기존 READY 개요 중 `quizCheckpoints`가 없는 행은 기존 개요 bounded backfill 배치에 포함해 순차 재생성한다.
- Main Service read timeout은 `EDUPILOT_AI_OUTLINE_TIMEOUT`(기본 `110s`)을 사용한다.

### 6.7 POST /internal/ai/criteria/suggest

- 요청은 `schemaVersion:"1.0"`, 기본 9종과 비활성을 포함한 `existingCriterionKeys`,
  `READY` 개요의 `materials[{title,materialSummary,sections}]`로 구성합니다.
- 응답은 `criteria[{key,name,description,rubric,allowedSources,weight,minimumEvidence}]`와
  `warnings[{type,message}]`를 반환합니다. Spring은 기존 평가 기준 검증을 재사용해 응답
  전체를 한 트랜잭션으로 등록하며 일부 성공은 허용하지 않습니다.
- Main Service read timeout은 기존 `EDUPILOT_AI_CRITERIA_READ_TIMEOUT`(기본 `90s`)을
  사용합니다.

### 6.8 POST /internal/ai/captions

- 요청: `{ "schemaVersion": "1.0", "pages": [{ "pageNumber": 1, "imageBase64": "...", "extractedText": "..." }] }`. 한 요청에는 최대 10페이지를 전달합니다.
- 응답: `{ "schemaVersion": "1.0", "captions": [{ "pageNumber": 1, "caption": "..." }], "warnings": [] }`. 개별 페이지를 설명할 수 없으면 `caption`은 `null`입니다.
- Spring은 PDF를 150DPI JPEG로 순차 렌더링하고 최대 폭 1600px, 품질 0.8로 저장합니다. 청크 전체 실패는 해당 청크를 건너뛰고 다음 청크를 계속 처리하며, 모든 시도가 끝나면 완료 시각을 기록합니다.
- 캡션이 있는 페이지 텍스트는 AI 요청 조립 시점에 `text + "\n\n[그림 설명] " + caption`으로 병합합니다. DB의 `text_content` 원문은 변경하지 않으며 이미지·base64는 대화 이력이나 QA digest에 포함하지 않습니다.
- Main Service read timeout은 `EDUPILOT_AI_CAPTIONS_READ_TIMEOUT`(기본 `75s`)을 사용합니다.

### 6.9 POST /internal/ai/doc-chat

- 요청: `{ "schemaVersion": "1.0", "contextDocs": [{"title":"자료 p.1-3","text":"..."}], "history": [{"role":"USER|ASSISTANT","content":"..."}], "question": "..." }`.
- `contextDocs`는 1~10개이며 Spring이 페이지 순서를 보존해 조립합니다. 외부 요청 history는 최대 50개를 받지만 내부 요청에는 최근 10개만 포함합니다.
- 응답: `{ "schemaVersion": "1.0", "answer": "...", "warnings": [{"type":"CONTEXT_TRUNCATED","message":"..."}] }`.
- 한 요청당 LLM을 동기 1회 호출하며 스트리밍하지 않습니다. Main Service read timeout은 `EDUPILOT_AI_DOCCHAT_READ_TIMEOUT`(기본 `75s`)을 사용합니다.

### 6.10 POST /internal/ai/conversation-summary

- 요청: `{ "schemaVersion": "1.0", "previousSummary": null, "messages": [{"role":"USER|ASSISTANT","content":"..."}] }`. `messages`는 1~20개이며 content는 공백일 수 없습니다. Spring은 8턴마다 비동기 요약을 트리거하고 마지막 요약 경계 이후의 완료 메시지를 시간순으로 전달합니다.
- 응답: `{ "schemaVersion": "1.0", "summary": "..." }`. 요약은 한국어 최대 1,000자이며 초과분은 AI Service가 결정적으로 절단합니다.
- 공백 요약 또는 구조화 출력 SCHEMA 실패만 총예산 안에서 1회 재생성하고 최종 실패는 `AI_RESPONSE_INVALID`입니다. 점수·채점 결과·평가 상태는 요약에 포함하지 않습니다.
- Spring은 최근 원문 메시지를 별도로 유지하고 이 엔드포인트를 턴 처리와 분리된 비동기 작업으로 호출합니다. 요약 실패는 사용자 턴을 실패시키지 않습니다.
- AI Service를 먼저 배포해야 합니다. 기존 AI 모델은 알 수 없는 `conversationSummary` 턴 필드를 `extra=forbid`로 거부하므로 Spring의 스냅샷 전달은 AI 배포 뒤에 활성화합니다. Main Service read timeout 권장은 `45s`입니다.

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
| `EDUPILOT_XAI_FILES_ENABLED` | `false` | 추출 성공 PDF의 xAI Files 업로드 kill switch |
| `EDUPILOT_XAI_FILE_UPLOAD_TIMEOUT_SECONDS` | `60` | xAI Files 업로드·삭제 HTTP timeout |
| `EDUPILOT_SUMMARY_TIMEOUT_SECONDS` | `30` | conversation-summary 총 시간 |
| `AGENT_REASONING_EFFORT` | `medium` | 기본 Agent 프로필 |
| `AGENT_MAX_TOKENS` | `16384` | 기본 최대 출력 토큰 |
| `AGENT_TEMPERATURE` | `null` | 선택적 temperature |
| `ORCHESTRATOR_REASONING_EFFORT` | `low` | Orchestrator 프로필 |
| `EXPLAINER_REASONING_EFFORT` | `medium` | ExplainerAgent 프로필 |
| `QA_REASONING_EFFORT` | `medium` | QaAgent 프로필 |
| `SUMMARY_REASONING_EFFORT` | `low` | conversation-summary 프로필 |

## 8. 확정 로그 및 문서 반영 대기

**v0.6에서 확정된 사항** (근거: #133·DEC-031 Accepted):

- grade의 `quizId` wire 타입은 integer이며 시험은 숫자 exam ID를 사용한다. `pageContext`와 `learnerMemoryDigest`는 optional·nullable이고 나머지 요청 필드는 required·non-null이다.
- 응답이 있는 SHORT·ESSAY만 유형별 호출하며 한 유형 실패 후에도 나머지 유형을 호출한다. `AI_REQUEST_INVALID`은 body·DTO 검증 실패 시 HTTP 422, 필드 간 의미 검증 실패 시 HTTP 400의 표준 SCHEMA 봉투로 반환하고 Spring은 두 경우 모두 내부 계약 결함으로 처리한다.

**v0.5에서 확정된 사항** (근거: #108 합의):

- `USER_QUESTION.payload.includeCurrentPage`는 선택 boolean이며 생략 시 `true`다. `false`이면 `xaiFileId`와 페이지 텍스트 3키를 null로 전달하고 그 외 context 필드는 유지한다. `currentPageText`의 null은 `USER_QUESTION`+`false`에서만 허용하고 `EXPLAIN_CURRENT_PAGE`·`QUIZ_TYPE_SELECTED`에서는 AI Service가 교차 검증한다.
- `includeCurrentPage=false`인 QaAgent는 일반 학습 지식으로 답변할 수 있지만 업로드 자료 내용을 추측하지 않고 학습 도우미 범위를 유지한다. QA thread와 `latestRepair` 승계는 플래그와 무관하다.
- 새 대화 마커 이후 스냅샷은 `recentMessages`를 마커 이후 메시지로 제한하고, 마커 이전 `qaThreadDigest`와 `latestRepair`를 null로 처리한다. `pendingDiagnosis`는 진단 회피를 막기 위해 유지하며 temporary memory candidates, quiz assessments, long-term learner memory도 유지한다. 대화 요약 기능 활성화 뒤에는 `conversationSummary`도 리셋한다.

**v0.4에서 확정된 사항** (근거: DEC-011·012·024 정합 + MVP 단순화):

- **학습자 메모리 스코프**: 수집(후보·quizAssessments 윈도우)=**세션 스코프**, 승격된 장기 메모리(learner_memories)·digest=**사용자×자료(user×material)**. DEC-024(자료당 ACTIVE 세션 1개)로 실사용상 두 스코프가 거의 일치하며, 세션 완료 후 새 세션에서도 개인화 유지가 장기 메모리의 존재 이유. → Epic7 ⓐ 계약에 명문화.
- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED` 삭제 (§3.2) → api-spec §5 갱신 필요.
- 타임아웃 표(§4)·heartbeat 10s·취소=fetch abort·재연결=재동기화 방식(§5) → Epic5 ⓐ에 그대로 반영, DEC-013 잔여 마감.
- usage 필드 표준 채택 (§3.3).

**보류(Deferred)**:

- 평가 리포트 PDF 출력 — MVP 이후. 재개 시 책임 분리안: 리포트 내용 JSON은 AI 서비스, PDF 렌더링은 Spring/FE (AI 서비스에 PDF 생성 책임 없음). 별도 이슈 + DEC 신규 항목으로 등록.

**타 문서 반영 대기 목록**: api-spec §9(SSE 세부), Epic5 ⓐ·Epic7 ⓐ 이슈 본문에 v0.4 확정값 기입. v0.5의 api-spec §5 payload 규칙은 이 문서 개정과 함께 동기화한다.
