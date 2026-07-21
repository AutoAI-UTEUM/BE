# [Feature] 학습 세션 생성·복원·페이지 상태 관리

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [간결한 Epic 초안](epics/04-learning-session.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: frontend
area: main-service
area: integration
type: feature
```

## 목표

사용자가 READY 상태의 PDF로 학습 세션을 시작하고, 현재 페이지와 메시지 상태를 복원하며, PDF 뷰어와 서버 상태를 LLM 호출 없이 일관되게 동기화할 수 있게 한다.

## 연결 요구사항

- `SESSION-001` 세션 생성
- `SESSION-002` 세션·메시지 복원
- `SESSION-003` 페이지 이동
- `SESSION-004` PDF 뷰어 동기화
- `SESSION-005` 학습 턴 이벤트 공통 경계
- `SESSION-006` 세션 완료
- `SESSION-007` 완료·삭제 세션의 변경 거부

## 사용자 흐름

1. 사용자가 READY 자료를 선택해 세션을 만든다.
2. 서버가 1페이지와 `NOT_EXPLAINED` 상태를 저장한다.
3. FE가 세션 화면과 PDF 1페이지를 표시한다.
4. 사용자가 다음/이전/특정 페이지로 이동한다.
5. Spring StateReducer가 범위를 검증하고 현재 페이지를 갱신한다.
6. 새로고침·재진입 시 서버 상태와 메시지를 복원한다.
7. 사용자가 세션을 완료한다.

## 범위

### 포함

- `LearningSession`, `ChatMessage` schema와 상태
- 세션 생성·조회·메시지 조회·완료 API
- 페이지 이동 API와 StateReducer
- 초기/페이지 이동 후 설명 여부 UI action
- 소유권·활성 상태·페이지 범위 검증
- 중복 요청/동시 상태 변경 기본 방어
- FE PDF 뷰어와 서버 상태 동기화

### 제외

- AI 설명·QA·퀴즈의 실제 생성
- 세션 공유
- 여러 사용자의 공동 세션
- 페이지별 진행 모델은 `DEC-008` 결과 이상으로 확장하지 않음

## 작업 후보 — 필요할 때만 Sub-issue 생성

- 세션 단일 pageStatus와 페이지별 progress 모델 확정
- `[Contract]` Session/Page/Message 외부 API 계약
- `[Main]` LearningSession/ChatMessage schema와 migration
- `[Main]` 세션 생성·조회·완료 구현
- `[Main]` 페이지 이동 StateReducer 구현
- `[Main]` 메시지 조회 페이지네이션 구현
- `[FE]` 세션 진입·복원·PDF 페이지 동기화
- `[Integration]` 생성→이동→새로고침→완료 흐름 테스트
- `[Concurrency]` 중복 페이지/턴 요청 충돌 테스트

## 외부 API 초안

```http
POST   /api/sessions
GET    /api/sessions
GET    /api/sessions/{sessionId}
DELETE /api/sessions/{sessionId}
PATCH  /api/sessions/{sessionId}/page
GET   /api/sessions/{sessionId}/messages
POST  /api/sessions/{sessionId}/complete
POST  /api/sessions/{sessionId}/turns
```

`turns` API에서는 이 workstream이 인증·검증·공통 이벤트 envelope까지만 소유하고, 실제 AI 행동은 AI 학습 턴 Epic에서 구현한다.

## 선행 의존성

- [인증과 사용자](01-auth-user.md)
- [READY 학습 자료](02-learning-material.md)
- `DEC-008` 페이지 진행 모델

## 주요 예외

- 처리 중/실패/삭제 자료로 세션 생성
- 타인 세션 조회·변경
- 페이지 번호가 1 미만 또는 pageCount 초과
- 완료·삭제 세션에 턴/이동 요청
- 동시에 들어온 충돌 상태 변경
- 동일 requestId 재전송

## 완료 조건

- [ ] 세션과 페이지 상태 모델이 승인됐다.
- [ ] READY 자료로 세션을 생성할 수 있다.
- [ ] 재진입 시 현재 페이지와 메시지를 복원할 수 있다.
- [ ] 페이지 이동이 LLM 없이 처리된다.
- [ ] 서버와 FE PDF 뷰어의 currentPage가 일치한다.
- [ ] 타인/완료 세션의 상태 변경이 거부된다.
- [ ] 페이지 범위와 상태 전이 테스트가 통과한다.
- [ ] 중복 요청 기본 정책이 적용됐다.
