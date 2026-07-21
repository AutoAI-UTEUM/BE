# [Feature] AI 응답 실시간 스트리밍

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

설명과 QA 등 긴 AI 응답을 사용자에게 점진적으로 표시하면서도, 중단·재전송·완료 시점에 메시지와 세션 상태가 중복되거나 손상되지 않게 한다.

## 연결 요구사항

- `AI-006` AI 응답 점진 표시
- 공통 예외: 스트리밍 중 연결 종료
- 비기능: AI timeout, 추적 ID, 멱등성

## 사용자 흐름

1. 사용자가 설명 또는 질문 이벤트를 전송한다.
2. Spring이 인증된 스트림을 열고 AI Service 호출을 시작한다.
3. AI Service가 진행 상태, 사용자 표시용 thought summary, content delta를 반환한다.
4. Spring이 허용된 이벤트만 FE로 중계한다.
5. FE가 임시 메시지로 청크를 렌더링한다.
6. 완료 이벤트와 최종 결과 검증 후 Main Service가 메시지와 상태를 확정 저장한다.
7. 중단 시 불완전 메시지는 확정 메시지로 처리하지 않는다.

## 범위

### 포함

- SSE 스트리밍 프로토콜
- 스트림 이벤트 schema
- 인증·연결 수명·취소·timeout
- 임시 content와 최종 메시지 구분
- 완료 후 한 번만 저장
- 연결 종료와 제한된 재연결 정책
- FE 점진 렌더링과 오류 UI
- 사용자 표시용 `thought_summary`

### 제외

- 내부 chain-of-thought 원문 노출·저장
- 모든 AI 도구의 스트리밍 동시 지원 — 설명/QA 우선
- WebSocket은 양방향 실시간 통신이 필수로 변경되어 별도 결정이 승인된 경우에만 사용

## 작업 후보 — 필요할 때만 Sub-issue 생성

- SSE 이벤트 schema, heartbeat, 취소·재연결·저장 시점 확정
- `[Contract]` 스트림 이벤트와 종료/오류 계약
- `[Main]` 인증 스트림 endpoint와 FastAPI 중계
- `[Main]` 최종 결과 검증·멱등 저장·취소 처리
- `[AI]` Gemini 스트리밍과 ToolDispatcher 이벤트 변환
- `[AI]` thought_summary의 공개 가능한 범위 구현
- `[FE]` content delta 렌더링·취소·재연결 UI
- `[Integration]` 정상 완료·중단·재전송·timeout 테스트

## 이벤트 후보

```text
status
thought_summary
content_delta
ui_action
completed
error
```

## 선행 의존성

- [비스트리밍 페이지 설명](04-page-explanation.md)
- [비스트리밍 QA](05-question-answer.md)
- `DEC-013` SSE 기본안과 남은 세부 계약

## 주요 예외

- 스트림 중 클라이언트 연결 종료
- Gemini timeout/rate limit
- completed 이벤트 전 DB 저장
- 재연결로 동일 메시지 중복 저장
- 일부 청크만 받은 메시지를 완료 처리
- 허용되지 않은 event type
- thought_summary에 내부 추론/민감정보 노출

## 완료 조건

- [ ] 스트리밍 프로토콜과 이벤트 schema가 승인됐다.
- [ ] 설명과 QA 응답이 점진적으로 표시된다.
- [ ] 임시 청크와 확정 메시지가 구분된다.
- [ ] 최종 결과가 검증된 후 한 번만 저장된다.
- [ ] 연결 종료가 세션 상태를 손상시키지 않는다.
- [ ] 취소·timeout·재전송 정책이 동작한다.
- [ ] 내부 chain-of-thought가 노출되거나 저장되지 않는다.
- [ ] 정상·중단·재연결 통합 테스트가 통과한다.
