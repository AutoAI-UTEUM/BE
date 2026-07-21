# [Feature] 학습 범위 기반 퀴즈 생성

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

사용자가 MCQ, OX, SHORT, ESSAY 중 퀴즈 유형을 선택하면 QuizAgent가 지정된 PDF 학습 범위와 학생 상태를 근거로 유효한 퀴즈를 생성하고, 정답·루브릭을 노출하지 않은 풀이 UI를 제공한다.

## 연결 요구사항

- `QUIZ-001` 네 가지 퀴즈 유형
- `QUIZ-002` 지정 페이지 범위 근거
- `QUIZ-003` 5~10개 문항 수 정책
- `SESSION-005` 퀴즈 유형 선택 턴

## 사용자 흐름

1. 설명 완료 후 퀴즈 진행/유형 선택 UI를 표시한다.
2. 사용자가 퀴즈 유형을 선택한다.
3. Main Service가 세션·페이지·범위를 검증한다.
4. AI Service의 QuizAgent가 구조화 퀴즈 JSON을 생성한다.
5. Main Service가 스키마와 문항 불변식을 검증한다.
6. 문제 원본과 비공개 정답/루브릭을 분리해 저장한다.
7. FE에는 풀이에 필요한 공개 문제만 반환한다.

## 범위

### 포함

- `QUIZ_TYPE_SELECTED` 이벤트
- MCQ/OX/SHORT/ESSAY JSON Schema
- 기본 5개, 정책상 5~10개 문항
- 페이지 범위·난이도·confidence 입력
- Quiz 영속화와 공개/비공개 DTO 분리
- FE 퀴즈 유형 선택과 풀이 UI
- 생성 오류와 재시도 정책

### 제외

- 제출·채점 — [퀴즈 제출·채점 상세 계획](07-quiz-grading.md)
- 저득점 진단·교정 — [평가·진단·교정 상세 계획](08-diagnosis-repair.md)
- 퀴즈 결과 장기 메모리 승격 — [학습자 메모리 상세 계획](09-learner-memory.md)

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` 퀴즈 유형별 JSON Schema와 외부 응답 계약
- `[Decision]` 퀴즈 범위·문항 수·난이도 정책 승인
- `[Main]` Quiz schema와 공개/비공개 데이터 분리
- `[Main]` QuizAgent 호출·스키마 검증·저장
- `[Main]` `GET /api/quizzes/{quizId}` 공개 문항 조회 API
- `[AI]` 유형별 QuizAgent 프롬프트와 구조화 출력
- `[AI]` learnerConfidence/범위 반영 정책 구현
- `[FE]` 유형 선택·MCQ/OX/SHORT/ESSAY 풀이 UI
- `[Security]` 제출 전 정답/루브릭 비노출 테스트
- `[Integration]` 네 가지 유형 생성 흐름 테스트

## 외부 API 초안

```http
POST /api/sessions/{sessionId}/turns
GET  /api/quizzes/{quizId}
GET  /api/sessions/{sessionId}/quizzes
```

퀴즈 생성 턴 응답은 `state.activeQuizId`로 참조만 전달하고, FE는 `GET /api/quizzes/{quizId}`로 공개 문항을 조회해 풀이 UI를 연다(새로고침 복원 포함).

```json
{
  "requestId": "quiz-request-001",
  "eventType": "QUIZ_TYPE_SELECTED",
  "payload": {
    "quizType": "MCQ"
  }
}
```

## 선행 의존성

- [세션과 턴 경계](03-learning-session.md)
- [페이지 설명 완료 상태](04-page-explanation.md)
- [PDF 페이지 범위](02-learning-material.md)

## 주요 예외

- 지원하지 않는 quizType
- 유효하지 않은 페이지 범위
- 생성 문항 수와 배열 길이 불일치
- 중복 questionId
- 유형별 필수 필드 누락
- AI가 PDF 범위를 벗어난 문제를 생성
- 정답/루브릭이 FE 응답에 포함됨
- AI timeout/스키마 오류

## 완료 조건

- [ ] 네 가지 유형의 JSON Schema가 승인됐다.
- [ ] PDF 범위와 학생 상태를 반영한 퀴즈가 생성된다.
- [ ] 문항 수가 합의된 5~10개 범위다.
- [ ] Main Service가 유형별 필수 필드와 불변식을 검증한다.
- [ ] 정답과 루브릭이 제출 전 FE에 노출되지 않는다.
- [ ] FE가 네 가지 퀴즈를 표시하고 답안을 입력받는다.
- [ ] 퀴즈 원본을 재현 가능하게 저장한다.
- [ ] 유형별 정상·스키마 실패·비노출 테스트가 통과한다.
