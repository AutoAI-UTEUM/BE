# 기능 명세서

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-07-23 |
| 목적 | 기능별 사용자 흐름, 정책, 예외 정의 |

## 1. 공통 정책

- FE는 사용자 이벤트를 Spring에 전달하며 에이전트를 직접 선택하지 않습니다.
- Spring은 인증, 소유권, 현재 상태, 입력 범위를 먼저 검증합니다.
- LLM 판단 없이 처리 가능한 상태 전이는 Spring `StateReducer`가 처리합니다.
- AI가 필요한 턴은 Spring이 세션 스냅샷과 이벤트를 FastAPI에 전달합니다.
- FastAPI 결과는 Spring이 계약과 상태 전이를 검증해 저장한 뒤 FE에 반환합니다.
- UI 액션은 문자열 문구만이 아니라 안정된 `type`과 `eventType`을 포함해야 합니다.

## 2. 인증

### 회원가입

1. 사용자가 이메일, 비밀번호, 이름을 입력합니다.
2. Spring은 형식과 이메일 중복을 검증합니다.
3. 비밀번호를 단방향 해시로 저장하고 사용자 정보를 반환합니다.

예외: 잘못된 형식, 중복 이메일, 비밀번호 정책 미충족.

### 로그인

1. 사용자가 이메일과 비밀번호를 제출합니다.
2. Spring은 자격 증명과 사용자 상태를 검증합니다.
3. JWT access token과 최소 사용자 정보를 반환합니다.

refresh token 정책은 구현 전에 별도로 확정합니다.

## 3. PDF 자료

### 업로드

1. 인증 사용자가 제목과 PDF 파일을 업로드합니다.
2. Spring은 권한, 콘텐츠 타입, 확장자, 크기 제한을 검증합니다.
3. 파일 저장 후 메타데이터와 처리 상태를 저장합니다.
4. 페이지 수와 페이지 텍스트 추출이 완료되면 학습 가능 상태로 전환합니다.

파일 저장소, 비동기 처리 여부, 제한값은 TBD입니다. 처리 중 자료로 세션을 만들 수 없습니다.

### 조회

- 목록은 본인이 업로드한 자료만 반환합니다(소유자 전용 — DEC-026).
- 상세는 페이지 수, 처리 상태 등 학습 시작에 필요한 메타데이터를 반환합니다.
- 페이지 텍스트 API의 운영 노출 여부는 보안·저작권 검토 후 결정합니다.

## 4. 학습 세션

### 세션 시작

1. 사용자가 학습 가능한 자료를 선택합니다.
2. Spring은 자료 접근 권한과 처리 상태를 검증합니다.
3. `currentPage=1`, `pageStatus=NOT_EXPLAINED`, `status=ACTIVE`인 세션을 만듭니다.
4. `강의를 시작할까요?` 선택 UI를 초기 액션으로 반환합니다.

동일 자료에 기존 ACTIVE 세션이 있으면 새로 만들지 않고 그 세션을 재사용합니다(DEC-024).

### 페이지 이동

1. 사용자가 이전/다음 또는 페이지 번호를 선택합니다.
2. FE가 `PATCH /api/sessions/{sessionId}/page`를 호출합니다.
3. Spring은 범위와 세션 소유권/상태를 검증합니다.
4. `currentPage`를 바꾸고 해당 페이지 상태를 초기화합니다.
5. `현재 페이지를 설명할까요?` UI 액션을 반환합니다.
6. FE는 응답의 페이지를 기준으로 PDF 뷰어를 동기화합니다.

이 흐름은 LLM을 호출하지 않습니다. 중복된 동일 페이지 요청은 안전하게 같은 상태를 반환해야 합니다.

### 세션 재진입

1. FE가 `GET /api/sessions`로 내 세션 목록을 조회해 재개할 세션을 선택하거나, 보유한 `sessionId`로 직접 진입합니다.
2. FE가 세션 상태와 메시지를 조회합니다.
3. Spring은 저장된 현재 페이지, 페이지 상태, 최근 메시지/페이지네이션 정보와 함께 진행 중이던 `uiActions`, `activeQuizId`를 반환합니다.
4. FE는 서버 상태를 기준으로 화면(선택 UI·퀴즈 풀이 포함)을 복원합니다.

### 세션 삭제

1. 사용자가 세션 삭제를 선택하면 FE가 `DELETE /api/sessions/{sessionId}`를 호출합니다.
2. Spring은 소유권과 진행 중 턴 충돌을 검증한 뒤 `status=DELETED`로 논리 삭제합니다.
3. 삭제된 세션은 목록·조회에서 제외되고, 이후 턴·페이지 이동·제출 요청은 `SESSION_NOT_ACTIVE`로 거부합니다(SESSION-007).

## 5. 페이지 설명

1. 사용자가 설명 시작을 선택하여 `EXPLAIN_CURRENT_PAGE` 이벤트를 보냅니다.
2. Spring은 중복 실행 여부를 확인하고 필요한 세션 문맥을 조회합니다.
3. FastAPI Orchestrator가 설명 필요성을 판단하고 검증된 Plan을 실행합니다.
4. ExplainerAgent는 현재 페이지 중심의 Markdown 설명을 생성합니다.
5. Spring은 최종 설명 메시지를 저장하고 페이지 상태를 `EXPLAINED`로 전환합니다.
6. FE에 설명과 다음 행동 UI를 반환합니다.

정책:

- 이전/다음 페이지는 연결 문맥으로만 사용합니다.
- 설명 깊이는 `NORMAL`, `DETAILED` 중 하나로 시작하고 확장은 별도 합의합니다.
- 같은 요청이 재전송되어 설명 메시지가 중복 생성되지 않도록 턴 식별자를 검토합니다.

## 6. 질문 답변

### 새 질문

1. 사용자가 `USER_QUESTION` 이벤트를 전송합니다.
2. Orchestrator가 새 질문인지 후속 질문인지 판단합니다.
3. `START_NEW`이면 새 QaThread를 만들거나 활성 흐름을 닫습니다.
4. QaAgent는 현재 페이지와 사용자 질문을 중심으로 답합니다.
5. 질문과 답변을 ChatMessage 및 QaThread 문맥에 저장합니다.

### 후속 질문

1. `FOLLOW_UP`이면 같은 QaThread의 요약/최근 문맥을 FastAPI에 전달합니다.
2. QaAgent는 이전 답변과 자연스럽게 이어서 답합니다.
3. 페이지나 설명 문맥이 바뀌면 기존 QaThread를 닫는 것을 기본으로 합니다.

근거가 부족한 질문은 추측하지 않고 자료 범위의 한계를 안내합니다.

교정(repair) 후 추가 질문도 별도 이벤트 없이 `USER_QUESTION`을 재사용합니다. 직전 교정이 있으면 Spring이 스냅샷의 `latestRepair`로 교정 답변 문맥을 승계해 전달하고, Orchestrator가 QaAgent를 선택해 이어서 답합니다.

## 7. 퀴즈 생성

1. 설명 후 시스템이 퀴즈 진행 여부 또는 유형 선택 UI를 표시합니다.
2. 사용자가 `MCQ`, `OX`, `SHORT`, `ESSAY` 중 하나를 선택합니다.
3. `QUIZ_TYPE_SELECTED` 이벤트로 FastAPI QuizAgent를 호출합니다.
4. QuizAgent는 페이지 범위, 학습자 상태, 약점, 난이도를 반영한 구조화 JSON을 반환합니다.
5. FastAPI와 Spring이 스키마를 검증합니다.
6. Spring은 문제 원본과 서버 전용 정답/루브릭을 분리해 저장합니다.
7. FE에는 풀이에 필요한 공개 필드만 반환합니다.

정답이나 루브릭은 퀴즈 제출 전 FE 응답에 포함하지 않습니다. 기본 문항 수는 5개이며 5~10개 범위 조절은 학습 정책에 따릅니다.

## 8. 퀴즈 제출과 채점

퀴즈 제출 이후는 **결정적 파이프라인**입니다. Spring이 이벤트 타입과 점수 기준에 따라 전용 내부 API를 순차 호출하며, Orchestrator 판단을 거치지 않습니다([API 명세](api-spec.md) §8 호출 주체 원칙).

### MCQ/OX

1. Spring이 제출 소유권, 상태, 문항 ID를 검증합니다.
2. 저장된 서버 전용 정답과 답안을 비교합니다.
3. 문항별 결과, 총점, 통과 여부를 계산하고 저장합니다.
4. 파이프라인 다음 단계로 `/internal/ai/quiz-assessment`를 호출해 내부 평가를 생성합니다.

### SHORT/ESSAY

1. Spring이 제출을 검증하고 파이프라인 1단계로 `/internal/ai/grade`(GraderAgent)를 호출합니다.
2. GraderAgent는 문제, 모범 답안, 루브릭, 학생 답안을 근거로 채점 JSON을 반환합니다.
3. Spring은 각 점수가 `0..maxScore`이고 합계가 일관적인지 검증합니다.
4. 결과를 저장한 후 파이프라인 2단계로 `/internal/ai/quiz-assessment`를 호출합니다.

채점 판정은 `CORRECT`, `PARTIAL`, `WRONG`만 사용합니다. MVP는 한 퀴즈당 1회 제출로 제한하며 재제출은 `QUIZ_ALREADY_SUBMITTED`로 거부합니다(DEC-009). 이후 재제출을 허용하려면 verdict/feedback 공개 시점, 점수 처리, attempt 상한을 함께 정의해야 합니다.

## 9. 저득점 진단과 오개념 교정

1. 채점 결과가 합의된 통과 기준 미만이면 파이프라인 3단계로 `/internal/ai/diagnosis`를 호출해 QuizDiagnosis를 생성합니다. 요청에는 직전 단계의 QuizAssessment, 오답 문항, 학생 답안, 강의 문맥을 포함합니다.
2. 진단 결과에는 `focusConcepts`, `suspectedMisconceptions`, `diagnosticPrompt`, `evidence`, `repairHint`가 포함됩니다.
3. Spring은 `Diagnosis(PENDING)`을 저장하고 진단 질문을 UI에 보냅니다.
4. 사용자가 `DIAGNOSIS_ANSWER_SUBMITTED` 이벤트로 답합니다. 이 이벤트부터는 자유 학습 턴으로 `/internal/ai/turn`에 전달합니다.
5. Orchestrator의 Plan에 따라 MisconceptionRepairAgent가 오답, 진단, 사용자 답변, 페이지 문맥을 참고해 짧은 교정 설명을 생성합니다.
6. Spring은 교정 결과와 진단 상태 `COMPLETED`를 저장합니다.

전체 페이지 재설명은 하지 않습니다. 진단 후 추가 질문은 별도 이벤트 없이 `USER_QUESTION`을 재사용하며, Spring이 스냅샷 `latestRepair`에 교정 답변 원문을 포함해 전달하면 Orchestrator가 RepairAgent가 아니라 QaAgent를 선택해 교정 답변 문맥을 이어받아 처리합니다.

통과 기준은 `score/maxScore >= 0.6`이며 `EDUPILOT_QUIZ_PASS_RATIO` 설정으로 관리합니다(DEC-010).

## 10. 평가 메모리와 장기 학습자 메모리

- 모든 채점 후 QuizAssessment를 생성하여 최근 평가 큐에 저장합니다.
- 큐 최대 개수와 정리 정책은 TBD입니다.
- 단일 결과는 장기 메모리의 확정 근거가 아닙니다.
- 여러 퀴즈, QA, 진단, 교정에서 같은 패턴이 반복되면 LearnerMemoryService가 임시 후보를 정리합니다.
- Orchestrator가 충분한 근거와 함께 `MemoryWrite`를 계획하고 정책 검증을 통과한 경우에만 장기 메모리로 승격합니다.
- 승격 이력에는 근거와 갱신 시각을 추적할 수 있어야 합니다.

## 11. 스트리밍

1. FastAPI/Gemini가 사용자 표시용 진행 요약과 답변 청크를 생성합니다.
2. Spring이 인증된 스트림으로 FE에 중계합니다.
3. FE는 청크를 임시 렌더링합니다.
4. 완료 이벤트 후 Spring이 최종 메시지와 상태를 확정 저장합니다.

연결이 끊기면 미완료 메시지를 확정 메시지로 취급하지 않습니다. 전송 방식은 SSE를 기본으로 하며, 세부 확정값(이벤트 종류, heartbeat 10초, fetch abort 취소, 재동기화 재연결, 최종 저장 시점)은 [API 명세](api-spec.md) SSE 스트리밍 절을 따릅니다.
