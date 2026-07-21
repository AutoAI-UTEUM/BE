# [Feature] 현재 페이지 기반 AI 질의응답 제공

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [AI 학습 턴 Epic 초안](epics/05-ai-learning-turn.md)을 사용합니다.

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

사용자가 현재 학습 중인 PDF 페이지에 관해 질문하면 QaAgent가 페이지 근거와 같은 흐름의 이전 QA 문맥을 반영해 답하고, 질문과 답변을 세션에서 이어갈 수 있게 한다.

## 연결 요구사항

- `SESSION-005` 사용자 질문 턴 이벤트
- `AI-003` 현재 페이지 기반 자유 질문 답변
- `AI-004` 새 질문과 후속 질문의 QaThread 구분
- `AI-005` 근거 부족 시 한계 안내

## 사용자 흐름

1. 사용자가 학습 세션 채팅창에서 질문한다.
2. Main Service가 인증·소유권·활성 세션을 검증하고, 활성 QaThread digest를 포함한 스냅샷을 AI Service에 전달한다.
3. AI Service의 Orchestrator가 새 질문(`START_NEW`)인지 후속 질문(`FOLLOW_UP`)인지 판단하고 QaAgent를 실행한다(판단 주체는 Orchestrator — feature-spec §6, agent-system-spec §9.2 기준).
4. Main Service가 turn 응답의 스레드 결정(statePatch `qaThread`)에 따라 새 QaThread 생성/기존 스레드 연결을 반영하고, 사용자 질문과 AI 답변을 저장한다.
5. FE가 답변을 표시하고 추가 질문을 받을 수 있게 한다.

## 범위

### 포함

- `USER_QUESTION` 이벤트
- `QaThread`, `QaMessage` 또는 합의된 메시지 모델
- `START_NEW`, `FOLLOW_UP` 판단·전달 계약
- QaAgent 페이지 근거 답변
- 근거 부족 안내
- 질문·답변 저장과 세션 재진입 복원
- FE 질문 입력·로딩·오류·답변 UI
- 비스트리밍 최종 응답

### 제외

- 일반 웹 검색
- PDF 범위 밖 사실의 임의 답변
- 스트리밍 — [AI 스트리밍 상세 계획](10-ai-streaming.md)
- 의미 있는 질문 패턴의 장기 메모리 승격 — [학습자 메모리 상세 계획](09-learner-memory.md)
- 오개념 교정 전용 답변 — [평가·진단·교정 상세 계획](08-diagnosis-repair.md)

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` USER_QUESTION/QaThread 외부·내부 API 계약
- `[Main]` QaThread/QaMessage schema와 migration
- `[Main]` 새 질문·후속 질문 문맥 조회와 저장
- `[Main]` 질문 턴 검증·AI 호출·멱등성
- `[AI]` QA용 ContextBuilder와 thread mode 처리
- `[AI]` QaAgent 프롬프트·구조화 응답 구현
- `[FE]` 질문 입력·답변·재시도 UI
- `[Integration]` 새 질문·후속 질문·재진입 흐름 테스트

## 외부 API 초안

```http
POST /api/sessions/{sessionId}/turns
```

```json
{
  "requestId": "question-request-001",
  "eventType": "USER_QUESTION",
  "payload": {
    "message": "편차가 무슨 뜻이야?"
  }
}
```

## 선행 의존성

- [세션·메시지 기반](03-learning-session.md)
- [현재 페이지 문맥](02-learning-material.md)
- [내부 AI 계약](00-foundation.md)

## 주요 예외

- 빈 질문 또는 제한 초과 질문
- 타인·완료 세션
- 현재 페이지 문맥 누락
- FOLLOW_UP인데 활성 QaThread가 없음
- 새 질문이 이전 thread 문맥과 섞임
- AI timeout/잘못된 응답
- 클라이언트 재전송에 따른 중복 메시지

## 완료 조건

- [ ] 사용자 질문 외부·내부 계약이 승인됐다.
- [ ] 현재 페이지 근거로 답변을 생성한다.
- [ ] START_NEW는 이전 thread를 사용하지 않는다.
- [ ] FOLLOW_UP은 같은 QA 문맥을 이어간다.
- [ ] 근거가 부족하면 추측 대신 한계를 알린다.
- [ ] 질문과 답변이 저장되고 재진입 시 복원된다.
- [ ] FE에서 질문·답변·오류·재시도가 동작한다.
- [ ] 새/후속 문맥 격리와 중복 요청 테스트가 통과한다.
