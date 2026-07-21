# [Feature] 현재 PDF 페이지 AI 설명 제공

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

사용자가 현재 PDF 페이지의 설명을 요청하면, ExplainerAgent가 현재 페이지를 중심으로 이해하기 쉬운 설명을 생성하고 Main Service가 검증·저장해 채팅 UI에 제공한다.

## 연결 요구사항

- `SESSION-005` 설명 턴 이벤트 처리
- `AI-001` 현재 페이지 설명
- `AI-002` 수준·메모리 반영 — 메모리 기능 전에는 null 허용
- `AI-005` 근거 부족 시 한계 안내

## 사용자 흐름

1. 새 세션 또는 페이지 이동 후 `현재 페이지를 설명할까요?` UI가 표시된다.
2. 사용자가 설명 시작을 선택한다.
3. Main Service가 세션·페이지·소유권을 검증한다.
4. Main Service가 페이지 문맥을 AI Service에 전달한다.
5. Orchestrator가 ExplainerAgent 호출 Plan을 만들고 Policy가 검증한다.
6. ExplainerAgent가 Markdown 설명을 반환한다.
7. Main Service가 메시지와 페이지 상태를 저장한다.
8. FE가 설명과 다음 행동 UI를 표시한다.

## 범위

### 포함

- `EXPLAIN_CURRENT_PAGE` 턴 이벤트
- Spring의 AI Client와 결과 검증/저장
- FastAPI ContextBuilder·Orchestrator·Policy·Dispatcher 최소 설명 흐름
- ExplainerAgent `NORMAL`, `DETAILED`
- 설명 메시지와 `pageStatus` 전이
- FE 설명 시작 선택과 Markdown 렌더링
- 비스트리밍 최종 응답

### 제외

- 스트리밍 — [AI 스트리밍 상세 계획](10-ai-streaming.md)
- 질문 답변 — [질의응답 상세 계획](05-question-answer.md)
- 퀴즈 생성 — [퀴즈 생성 상세 계획](06-quiz-generation.md)
- 장기 메모리 승격 — [학습자 메모리 상세 계획](09-learner-memory.md)

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` 설명 턴 외부/내부 API와 UI action 계약
- `[Main]` EXPLAIN_CURRENT_PAGE 검증·AI 호출·저장
- `[Main]` pageStatus 전이와 중복 턴 방어
- `[AI]` ContextBuilder 설명 문맥 구성
- `[AI]` Orchestrator/Policy의 설명 Plan 구현
- `[AI]` ExplainerAgent와 구조화 응답 구현
- `[FE]` 설명 선택 UI와 Markdown 메시지 렌더링
- `[Integration]` 페이지 설명 정상·실패 흐름 테스트

## 외부 API 초안

```http
POST /api/sessions/{sessionId}/turns
```

```json
{
  "requestId": "explain-request-001",
  "eventType": "EXPLAIN_CURRENT_PAGE",
  "payload": {
    "detailLevel": "NORMAL"
  }
}
```

## 선행 의존성

- [학습 세션과 페이지 문맥](03-learning-session.md)
- [페이지 텍스트](02-learning-material.md)
- [Spring-FastAPI 계약 기반](00-foundation.md)

## 주요 예외

- 현재 페이지 문맥 없음
- 완료·타인 세션
- 이미 실행 중인 동일 설명 요청
- FastAPI/Gemini timeout
- 에이전트 JSON 스키마 오류
- 허용되지 않은 statePatch/action
- AI 성공 후 DB 저장 실패

## 완료 조건

- [ ] 설명 턴 외부/내부 계약이 승인됐다.
- [ ] 사용자가 현재 페이지 설명을 요청할 수 있다.
- [ ] 설명은 현재 페이지 중심이며 인접 페이지는 보조로만 사용한다.
- [ ] Main Service가 AI 결과와 상태 패치를 검증한다.
- [ ] 최종 설명 메시지와 상태가 한 번만 저장된다.
- [ ] FE가 Markdown 설명과 다음 행동 UI를 표시한다.
- [ ] timeout·잘못된 JSON·중복 요청 테스트가 통과한다.
- [ ] 실제 Gemini 없이 CI 계약 테스트가 통과한다.
