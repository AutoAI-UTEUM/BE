# [Feature] 퀴즈 평가·저득점 진단·오개념 교정

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [학습 지원 Epic 초안](epics/07-learning-support.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: frontend
area: main-service
area: ai-service
area: integration
type: feature
```

## 목표

채점된 퀴즈를 내부 평가 메모로 변환하고, 기준 미달이면 학생에게 막힌 지점을 먼저 질문한 뒤 확인된 개념만 짧게 교정한다.

## 연결 요구사항

- `QUIZ-008` QuizAssessment 생성·저장
- `LEARN-001` 저득점 진단 질문
- `LEARN-002` 진단 답변 기반 교정
- `LEARN-003` 교정 후 추가 질문을 QaAgent로 연결
- `LEARN-004` 최근 평가 큐 — 상세는 `DEC-011`(Accepted: 전량 보존 + 세션 5 / 승격용 교차 세션 20 조회 윈도우)

## 사용자 흐름

1. 퀴즈 채점이 완료된다.
2. QuizAssessmentService가 강점·약점·오개념 후보를 정리한다.
3. 통과하면 다음 학습 행동을 제안한다.
4. 기준 미달이면 QuizDiagnosisService가 짧은 진단 질문을 생성한다.
5. Main Service가 `Diagnosis(PENDING)`을 저장하고 FE에 질문을 표시한다.
6. 사용자가 진단 답변을 제출한다.
7. MisconceptionRepairAgent가 확인된 혼동 지점만 교정한다.
8. Main Service가 Diagnosis를 완료하고 RepairResult를 저장한다.
9. 교정 후 추가 질문은 QaAgent 흐름으로 이어간다.

## 범위

### 포함

- QuizAssessment 영속화와 최근 평가 조회
- QuizDiagnosisService 구조화 출력
- Diagnosis `PENDING → ANSWERED → COMPLETED`
- `DIAGNOSIS_ANSWER_SUBMITTED` 이벤트
- MisconceptionRepairAgent
- RepairResult 저장
- 진단 질문·답변·교정 FE UI
- 교정 후 QA handoff

### 제외

- 단일 결과의 장기 LearnerMemory 확정 — [학습자 메모리 상세 계획](09-learner-memory.md)
- 전체 페이지 재설명
- 진단 없는 즉시 오개념 교정

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Decision]` 통과 기준과 평가 큐 크기 확정
- `[Contract]` Assessment/Diagnosis/Repair 내부·외부 계약
- `[Main]` QuizAssessment/Diagnosis/RepairResult schema와 migration
- `[Main]` 채점 후 Assessment·Diagnosis 오케스트레이션
- `[Main]` 진단 상태 전이와 답변 제출 검증
- `[AI]` QuizAssessmentService 구현
- `[AI]` QuizDiagnosisService 구현
- `[AI]` MisconceptionRepairAgent와 QA handoff 구현
- `[FE]` 저득점 진단 질문·답변·교정 UI
- `[Integration]` 고득점/저득점/진단/교정 전체 흐름 테스트

## 외부 API 초안

```http
POST /api/quizzes/{quizId}/submit
POST /api/sessions/{sessionId}/turns
```

진단 답변 이벤트:

```json
{
  "requestId": "diagnosis-answer-001",
  "eventType": "DIAGNOSIS_ANSWER_SUBMITTED",
  "payload": {
    "diagnosisId": 30,
    "answer": "역수로 바꾸는 이유가 헷갈려요."
  }
}
```

## 선행 의존성

- [검증된 채점 결과](07-quiz-grading.md)
- [교정 후 QA handoff](05-question-answer.md)
- `DEC-010` 통과 기준 (Accepted — 60%)
- `DEC-011` 평가 큐 (Accepted)

## 주요 예외

- 통과한 제출에 진단 생성
- 이미 pending 진단이 있는데 중복 생성
- 타인/완료 진단 답변
- PENDING이 아닌 진단에 답변
- 진단 답변 전에 RepairAgent 호출
- 전체 페이지를 다시 설명하는 교정 결과
- Assessment/Diagnosis JSON 스키마 오류
- 단일 진단 결과를 장기 메모리로 확정

## 완료 조건

- [ ] 채점 후 QuizAssessment가 생성·저장된다.
- [ ] 고득점과 저득점 후속 흐름이 분리된다.
- [ ] 저득점에서는 교정 전에 진단 질문이 표시된다.
- [ ] 진단 상태 전이가 유효한 순서로만 진행된다.
- [ ] 교정은 확인된 혼동 지점에 집중한다.
- [ ] 교정 후 추가 질문이 QaAgent로 이어진다.
- [ ] 단일 결과가 장기 메모리를 직접 변경하지 않는다.
- [ ] 고득점·저득점·중복·실패 통합 테스트가 통과한다.
