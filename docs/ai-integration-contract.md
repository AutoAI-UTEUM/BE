# ai-integration-contract v0.4 — Spring ↔ AI Service 통합 계약

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 v0.4 — 잔여 확정 항목 해소 (메모리 스코프·이벤트 정리·타임아웃·SSE 세부·usage) |
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
  "actionsExecuted": [ { "actionId": "action-1", "agent": "QaAgent", "status": "SUCCESS" } ],
  "messages": [ { "messageType": "QA", "content": "..." } ],
  "statePatch": {},
  "uiActions": [],
  "memoryCandidates": [],
  "usage": { "model": "grok-4.5-<date>", "inputTokens": 0, "outputTokens": 0, "reasoningTokens": 0 }
}
```

- `messageType`: `EXPLANATION | QA | DIAGNOSIS | REPAIR | SYSTEM` `[Epic5 ⓐ에서 확정]`
- `usage`: **채택 확정** — 모든 내부 응답의 표준 선택 필드 (reasoningTokens 포함, 미제공 시 null). Spring은 로그로만 수집(DB 저장 없음) — DEC-002 비용 트리거(월 $150) 판단 데이터.

### 3.4 statePatch 허용목록 (api-spec §10 표와 동일 — Spring이 이외 전부 거부)

| 필드 | 허용 값 | 비고 |
| --- | --- | --- |
| `pageStatus` | `EXPLAINING, EXPLAINED, QUIZ_READY, DIAGNOSIS_PENDING, REPAIR_COMPLETED` | `NOT_EXPLAINED` 역전이는 페이지 이동(StateReducer)만 |
| `activeQuizId` | 퀴즈 ID 또는 null | 생성 턴에서 설정, 제출 완료 시 Spring 해제 |
| `pendingDiagnosis` | 진단 참조 또는 null | 해제는 교정 완료 턴에서만 |
| `qaThread` | `{ "mode": "START_NEW"\|"FOLLOW_UP", "threadRef": ... }` | Orchestrator 스레드 결정 반영 |

- 세션 `status` 전이는 statePatch 불허 (외부 API 전용).

### 3.5 턴 내부 파이프라인 (FastAPI)

```
스냅샷 → ContextBuilder → Orchestrator(Grok structured output, Plan)
      → Policy/Verifier(스키마·허용 도구·교수 정책·승격 검증)
      → ToolDispatcher(순차 실행·부분 실패 명시·statePatch 병합) → 표준 응답/스트림
```

- 허용 도구 목록·Plan 검증 기준은 agent-system-spec §6~§7 기준으로 Epic5 ⓐ에서 확정.
- 파이프라인 전용 도구(grade/assessment/diagnosis)가 Plan에 나오면 Policy가 거부.
- Plan 스키마 실패 시 1회 재생성 fallback 후 category `SCHEMA`.
- Policy 강제 규칙 (Epic7): 진단 답변 전 Repair 금지, 메모리 승격 = 독립 근거 ≥2회 + confidence ≥0.7 (DEC-012), 근거 없는 성격·능력 추론 금지.

## 4. 멱등성 · 타임아웃 · 재시도 (v0.3 확정 제안 — DEC-002 §5 이관분)

- **멱등성 책임 분리**: 멱등의 원천은 Spring의 `requestId`(UK(session_id, request_id)) — FastAPI는 무상태이므로 자체 멱등 저장을 하지 않는다. `turnId`는 Spring이 턴마다 새로 발급하는 추적 ID이며, **재시도 시에도 새 turnId 발급**(같은 requestId → Spring이 저장 단계에서 중복 차단). 이로써 v2 패널 지적(재시도 vs DUPLICATE_TURN 충돌)은 구조적으로 해소.
- **타임아웃 (확정 — env로 관리, extract는 #5 실측 후 조정 여지)**:

| 엔드포인트 | 제안 timeout | 근거 |
| --- | --- | --- |
| turn (스트리밍) | 180s (첫 이벤트 30s) | 설명·퀴즈 생성 상한 |
| grade | 90s | high effort + ESSAY 다문항, 비스트리밍 |
| quiz-assessment / diagnosis | 45s | 구조화 출력 단건 |
| extract | 120s | 45MB·300p 상한 (실측 후 조정 — #5 체크리스트) |

- **재시도**: `retryable=true`인 category(TIMEOUT, INTERNAL 일부)만 Spring이 최대 1회. SCHEMA는 FastAPI 내부 1회 재생성으로 소진했으므로 Spring 재시도 없음. 부분 결과 반환 금지(전부 아니면 실패).

## 5. 스트림 이벤트 (표준 5+1종 — DEC-013 세부는 Epic5 ⓐ에서 마감)

내부 turn 스트림(FastAPI→Spring)과 외부 SSE(`GET /api/sessions/{id}/stream`, DEC-021 fetch 스트림)가 같은 이벤트 어휘를 쓴다:

| type | 필드 | 설명 |
| --- | --- | --- |
| `status` | `stage` | 진행 단계 (PLANNING / EXPLAINING / GENERATING 등) |
| `thought_summary` | `text` | 짧은 진행 요약 (내부 추론 원문 금지, 비저장) |
| `content_delta` | `text` | 본문 청크 (Markdown) |
| `ui_action` | `action` | 위젯 제안 |
| `completed` | `result` | 최종 turn 응답 (§3.3 구조) — **정확히 1회, 마지막** |
| `error` | `code, category, message` | 실패 종료 — completed와 상호 배타 |

- 불변식: `content_delta` 누적 == `completed.result.messages[].content`. 중단 시 Spring은 미확정 처리(청크 저장 금지).
- **heartbeat: 10초 (확정)** — SSE comment 라인(이벤트 아님), FE 무시.
- **취소 (확정, MVP)**: 별도 취소 API 없음 — FE fetch abort → Spring 연결 종료 감지 → FastAPI 상류 요청 취소.
- **재연결 (확정, MVP)**: `Last-Event-ID` 재전송 미지원 — 재연결 시 FE가 `GET /api/sessions/{id}` + `GET messages`로 재동기화, 진행 중 턴은 완료 후 확정 메시지로 수신. 중간 청크는 비확정이라 유실 무해. (서버측 버퍼·재전송은 이후 개선 항목 — DEC-013 잔여 마감)
- 스트리밍 경로는 Explainer·QA 우선, 그 외 도구는 비스트리밍 (#25 확정).

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
- env: `XAI_API_KEY`, `MODEL_NAME`, `EDUPILOT_INTERNAL_TOKEN` (+타임아웃 계열은 §4 확정치).

## 8. 확정 로그 및 문서 반영 대기

**v0.4에서 확정된 사항** (근거: DEC-011·012·024 정합 + MVP 단순화):

- **학습자 메모리 스코프**: 수집(후보·quizAssessments 윈도우)=**세션 스코프**, 승격된 장기 메모리(learner_memories)·digest=**사용자×자료(user×material)**. DEC-024(자료당 ACTIVE 세션 1개)로 실사용상 두 스코프가 거의 일치하며, 세션 완료 후 새 세션에서도 개인화 유지가 장기 메모리의 존재 이유. → Epic7 ⓐ 계약에 명문화.
- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED` 삭제 (§3.2) → api-spec §5 갱신 필요.
- 타임아웃 표(§4)·heartbeat 10s·취소=fetch abort·재연결=재동기화 방식(§5) → Epic5 ⓐ에 그대로 반영, DEC-013 잔여 마감.
- usage 필드 표준 채택 (§3.3).

**보류(Deferred)**:

- 평가 리포트 PDF 출력 — MVP 이후. 재개 시 책임 분리안: 리포트 내용 JSON은 AI 서비스, PDF 렌더링은 Spring/FE (AI 서비스에 PDF 생성 책임 없음). 별도 이슈 + DEC 신규 항목으로 등록.

**타 문서 반영 대기 목록**: api-spec §5(이벤트 삭제)·§9(SSE 세부), Epic5 ⓐ·Epic7 ⓐ 이슈 본문에 위 확정값 기입.
