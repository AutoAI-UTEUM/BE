# [Feature] 퀴즈 제출·채점·결과 저장

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [퀴즈 Epic 초안](epics/06-quiz.md)을 사용합니다.

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

사용자가 퀴즈 답안을 제출하면 Main Service가 소유권·상태·답안 구조를 검증하고, MCQ/OX는 결정적으로 채점하며 SHORT/ESSAY는 GraderAgent 결과를 검증해 일관된 채점 결과를 저장·표시한다.

## 연결 요구사항

- `QUIZ-004` MCQ/OX Spring 채점
- `QUIZ-005` SHORT/ESSAY AI 채점
- `QUIZ-006` 제출 소유권·상태 검증
- `QUIZ-007` 재제출 정책 — `DEC-009`
- `QUIZ-008` 채점 후 Assessment 생성 경계

## 사용자 흐름

1. 사용자가 퀴즈 답안을 제출한다.
2. Main Service가 사용자·세션·퀴즈·문항 ID를 검증한다.
3. MCQ/OX는 저장된 정답과 비교해 즉시 채점한다.
4. SHORT/ESSAY는 AI Service GraderAgent를 호출한다.
5. Main Service가 점수 범위·합계·판정 enum을 재검증한다.
6. 제출과 채점 결과를 저장한다.
7. FE가 문항별 피드백과 총점을 표시한다.

## 범위

### 포함

- QuizSubmission schema와 attempt 정책
- 제출 API와 멱등성
- MCQ/OX 결정적 채점
- SHORT/ESSAY GraderAgent
- `CORRECT`, `PARTIAL`, `WRONG`
- 총점·만점·통과 여부 계산
- 채점 결과/피드백 FE 표시
- 채점 완료 후 Assessment 호출 가능 상태

### 제외

- QuizAssessment 상세 분석 — [평가·진단·교정 상세 계획](08-diagnosis-repair.md)
- 저득점 진단·교정 — [평가·진단·교정 상세 계획](08-diagnosis-repair.md)
- 장기 메모리 — [학습자 메모리 상세 계획](09-learner-memory.md)

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Decision]` 재제출·attempt 정책 확정
- `[Decision]` 기본 통과 기준 확정
- `[Contract]` 퀴즈 제출·채점 결과 계약
- `[Main]` QuizSubmission schema와 migration
- `[Main]` 제출 검증·멱등성·MCQ/OX 채점
- `[Main]` AI 채점 결과 불변식 검증·저장
- `[AI]` SHORT/ESSAY GraderAgent와 루브릭 채점
- `[FE]` 답안 제출·채점 결과·피드백 UI
- `[Integration]` 네 유형 제출·재전송·실패 테스트

## 외부 API 초안

```http
POST /api/quizzes/{quizId}/submit
```

```json
{
  "requestId": "submission-request-001",
  "answers": [
    {
      "questionId": "q1",
      "answer": "2"
    }
  ]
}
```

## 선행 의존성

- [유효한 퀴즈와 비공개 정답/루브릭](06-quiz-generation.md)
- `DEC-009` 재제출 정책
- `DEC-010` 통과 기준

## 주요 예외

- 타인 세션의 퀴즈 제출
- 알 수 없거나 중복된 questionId
- 필수 답안 누락
- 이미 제출한 퀴즈 재제출
- AI 점수가 0..maxScore 범위를 벗어남
- 문항별 점수 합계와 총점(score) 불일치
- GraderAgent timeout/잘못된 verdict
- AI 성공 후 저장 실패와 클라이언트 재전송

## 완료 조건

- [ ] 재제출과 통과 기준 정책이 승인됐다.
- [ ] MCQ/OX 채점이 같은 입력에 항상 같은 결과를 낸다.
- [ ] SHORT/ESSAY가 루브릭에 따라 채점된다.
- [ ] Main Service가 AI 점수·합계·판정 불변식을 검증한다.
- [ ] 제출 소유권과 상태가 검증된다.
- [ ] 중복 requestId가 중복 제출을 만들지 않는다.
- [ ] FE가 총점과 문항별 결과를 표시한다.
- [ ] 네 유형 정상·실패·재전송 테스트가 통과한다.
