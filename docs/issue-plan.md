# EduPilot 상세 작업 분해 계획

| 항목 | 내용 |
| --- | --- |
| 상태 | 구현 참고용 설계 초안 |
| 마지막 갱신 | 2026-07-20 |

이 문서는 요구사항을 구현 가능한 작업 흐름으로 자세히 나눈 내부 계획이다. 아래 문서는 사용자 흐름, 포함·제외 범위, API 초안, 예외, 테스트 관점을 검토할 때 사용한다. **GitHub Epic 본문에 그대로 복사하지 않는다.**

실제 GitHub 부모 이슈는 [8개 Epic 초안](issues/README.md)을 사용한다. 상세 계획이 변경되면 요구사항·기능 명세·API 계약을 먼저 확인하고, Epic에는 일정과 완료 여부에 필요한 요약만 반영한다.

## 상세 Workstream

| Workstream | 상세 문서 | 포함되는 Epic |
| --- | --- | --- |
| 프로젝트 기반·공통 계약 구축 | [기반 및 계약 기준선](issues/00-foundation.md) | 프로젝트 기반·공통 계약 구축 |
| 인증·사용자 | [인증과 사용자](issues/01-auth-user.md) | 인증·사용자 |
| 자료 처리 | [PDF 학습 자료](issues/02-learning-material.md) | PDF 학습 자료 처리 |
| 세션 상태 | [학습 세션](issues/03-learning-session.md) | 학습 세션·페이지 상태 |
| 페이지 설명 | [페이지 설명](issues/04-page-explanation.md) | AI 학습 턴: 페이지 설명·질의응답·스트리밍 |
| 질의응답 | [질의응답](issues/05-question-answer.md) | AI 학습 턴: 페이지 설명·질의응답·스트리밍 |
| 퀴즈 생성 | [퀴즈 생성](issues/06-quiz-generation.md) | 퀴즈 생성·제출·채점 |
| 퀴즈 채점 | [퀴즈 제출·채점](issues/07-quiz-grading.md) | 퀴즈 생성·제출·채점 |
| 평가·진단·교정 | [평가·진단·교정](issues/08-diagnosis-repair.md) | 평가·진단·오개념 교정·학습자 메모리 |
| 장기 메모리 | [학습자 메모리](issues/09-learner-memory.md) | 평가·진단·오개념 교정·학습자 메모리 |
| AI 스트리밍 | [AI 스트리밍](issues/10-ai-streaming.md) | AI 학습 턴: 페이지 설명·질의응답·스트리밍 |
| 로깅·관측 | [로깅·관측](issues/11-observability.md) | 배포·운영·관측 |
| 배포·운영 | [배포·운영](issues/12-deployment.md) | 배포·운영·관측 |

## 사용 원칙

- Workstream은 GitHub 이슈 번호가 아니며 별도의 내부 부모 ID를 부여하지 않는다.
- 상세 API 예시는 합의 전 초안이다. 승인된 OpenAPI가 최종 기준이다.
- 모든 역할별 항목을 이슈로 만들지 않는다. 독립 담당자·PR·차단 관계가 있을 때만 Sub-issue로 승격한다.
- `DEC-XXX`는 [결정 대기 목록](decisions.md)의 문서 ID다. 실제 차단 결정만 GitHub `[Decision]` 이슈로 승격한다.
- 상세 계획과 제품 요구사항이 충돌하면 `requirements.md`, `feature-spec.md`, 승인된 OpenAPI 순으로 다시 확인한다.
