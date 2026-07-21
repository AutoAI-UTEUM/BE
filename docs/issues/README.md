# EduPilot GitHub Epic 초안

| 항목 | 내용 |
| --- | --- |
| 상태 | GitHub 등록 전 초안 |
| 마지막 갱신 | 2026-07-20 |
| 기준 저장소 | `AutoAI-EduPilot/edupilot-be` |

이 디렉터리는 실제 GitHub 부모 이슈로 사용할 **짧은 Epic 초안**과 구현 시 참고할 **상세 작업 분해 자료**를 분리해 관리한다.

- 실제 GitHub에 등록: [`epics/`](epics/)의 8개 문서
- 기능별 상세 흐름·예외·계약 검토: [상세 작업 분해 계획](../issue-plan.md)
- 최종 기능 명세와 API 계약: `docs/feature-spec.md`, OpenAPI, `docs/api-spec.md`

Epic 본문에는 목적, 범위, 제외 범위, 핵심 하위 작업, 완료 조건만 둔다. API JSON, 전체 예외 목록, 세부 상태 전이는 원본 문서를 복사하지 않고 관련 문서 링크로 연결한다.

## Epic 목록

| 순서 | Epic | 우선순위 | 선행 관계 |
| ---: | --- | --- | --- |
| 1 | [프로젝트 기반·공통 계약 구축](epics/01-foundation-contract.md) | High | 없음 |
| 2 | [인증·사용자](epics/02-auth-user.md) | High | 기반·공통 계약 |
| 3 | [PDF 학습 자료 처리](epics/03-material-pdf.md) | High | 기반·공통 계약, 인증 |
| 4 | [학습 세션·페이지 상태](epics/04-learning-session.md) | High | 인증, READY 학습 자료 |
| 5 | [AI 학습 턴: 페이지 설명·질의응답·스트리밍](epics/05-ai-learning-turn.md) | High | 학습 세션·페이지 상태 |
| 6 | [퀴즈 생성·제출·채점](epics/06-quiz.md) | High | 학습 세션, AI 학습 턴 |
| 7 | [평가·진단·오개념 교정·학습자 메모리](epics/07-learning-support.md) | High | 퀴즈 채점, QA |
| 8 | [배포·운영·관측](epics/08-operations.md) | High | 기반부터 병행, MVP 통합 후 완료 |

`AI 스트리밍`과 `장기 학습자 메모리`는 MVP Must 범위다. 일정 부족을 이유로 비스트리밍 응답이나 평가·진단 기록만 구현하고 Epic을 완료 처리하지 않는다. 사용자용 메모리 조회 화면(`LEARN-006`)은 별도 Could 범위로 유지한다.

## 등록 방법

1. 해당 Epic 문서를 Backend 저장소 이슈 본문에 복사한다.
2. 별도 내부 부모 번호는 만들지 않고 GitHub 이슈 번호를 기준으로 참조한다.
3. Project의 `Status`, `Priority`를 설정하고 `area:*`, `type:*` 라벨을 붙인다.
4. 문서의 하위 작업 체크박스 중 별도 추적이 필요한 것만 실제 Sub-issue로 만든다.
5. 생성한 Sub-issue URL을 해당 체크박스에 연결한다.
6. 모든 필수 하위 작업과 통합 완료 조건을 확인한 뒤 Epic을 닫는다.

## Sub-issue 생성 기준

다음 중 하나에 해당할 때만 별도 이슈로 분리한다.

- 담당자 또는 저장소가 다르다.
- 독립 PR이 필요하다.
- 다른 작업을 막거나 별도 완료 상태를 추적해야 한다.
- 계약 승인 또는 통합 테스트가 독립 산출물이다.

역할이 다르다는 이유만으로 `[Contract]`, `[Main]`, `[AI]`, `[FE]`, `[Integration]` 이슈를 모두 자동 생성하지 않는다. 한 담당자와 한 PR로 끝나는 작은 작업은 Epic 체크박스 또는 하나의 작업 이슈로 관리한다.

## Decision 운영

`DEC-001` 같은 값은 [결정 대기 목록](../decisions.md)의 문서 식별자이며 GitHub 이슈 번호가 아니다. 기본적으로 Epic의 `결정 필요` 체크박스로 관리하고, 여러 팀의 합의가 필요하거나 개발을 실제로 막는 결정만 별도 `[Decision]` 이슈로 만든다.

## 현재 만들지 않는 Epic

- 상세 관리자 기능
- `TEACHER`, Course, Lecture, Assignment
- Notification
- 리포트·통계 대시보드
- 결제, 실시간 화상 강의, 교사-학생 실시간 채팅

위 항목은 현재 MVP 요구사항에 포함되지 않는다. 범위가 승인되면 요구사항 문서를 먼저 갱신한 뒤 Epic을 추가한다.

## 관련 문서

- [상세 작업 분해 계획](../issue-plan.md)
- [이슈·PR 협업 작업 흐름](../issue-workflow.md)
- [요구사항](../requirements.md)
- [기능 명세](../feature-spec.md)
- [Definition of Done](../definition-of-done.md)
