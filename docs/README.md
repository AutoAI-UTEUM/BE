# EduPilot 문서 인덱스

| 항목 | 내용 |
| --- | --- |
| 상태 | 구현 전 설계 초안 |
| 마지막 갱신 | 2026-07-23 |
| 기준 | Spring 백엔드 중심 시스템 설계 |

## 문서 사용 원칙

이 문서 묶음은 프로젝트를 바로 생성하기 위한 코드가 아니라, 구현 전 팀 합의와 이후 개발 판단의 기준입니다.

- **확정**: 현재 팀 기준으로 채택한 내용입니다.
- **초안**: 구현 전에 FE/BE/AI 팀이 계약을 검토해야 합니다.
- **TBD**: 근거 없이 임의 확정하지 않고 결정이 필요한 내용입니다.

내용이 충돌하면 다음 순서로 판단합니다.

1. 팀에서 승인해 기록한 결정과 실제 OpenAPI/마이그레이션
2. [시스템 아키텍처](architecture.md)와 [API 명세](api-spec.md) — 단, Spring↔FastAPI 내부 API 관련 내용은 [Spring↔AI 통합 계약](ai-integration-contract.md) v0.4가 API 명세와 동급 이상의 기준이며 충돌 시 통합 계약이 우선합니다
3. [Spring 백엔드 실행 계획](backend-plan.md)
4. [에이전트 시스템 명세](agent-system-spec.md) — FastAPI 내부 구현 및 Spring-FastAPI 계약 범위

에이전트 원안의 `JsonStore`는 최종 구조에서 MySQL 기반 Spring 저장 계층으로 대체합니다. 에이전트 명세에 나타나는 `SystemState`는 논리적 런타임 상태이며, 영속 상태의 기준은 Spring과 MySQL입니다.

## 문서 목록

### 제품과 계약

- [프로젝트 목표](project-goals.md)
- [요구사항 명세](requirements.md)
- [기능 명세](feature-spec.md)
- [화면-API 매핑](screen-api-map.md)
- [API 명세](api-spec.md)
- [Spring↔AI 통합 계약](ai-integration-contract.md) — Spring↔FastAPI 내부 계약 v0.4. 내부 API 관련 내용이 충돌하면 API 명세와 동급 이상 기준
- [에러 코드](error-code.md)

### 백엔드 설계

- [시스템 아키텍처](architecture.md)
- [도메인 모델](domain-model.md)
- [데이터베이스](database.md)
- [백엔드 실행 계획](backend-plan.md)
- [백엔드 컨벤션](backend-convention.md)
- [배포·롤백 운영 가이드](deploy.md)
- [Definition of Done](definition-of-done.md)

### 협업과 AI

- [Git Flow](git-flow.md)
- [GitHub 이슈·PR 협업 작업 흐름](issue-workflow.md)
- [GitHub Epic 초안](issues/README.md)
- [상세 작업 분해 계획](issue-plan.md)
- [에이전트 시스템 명세](agent-system-spec.md)
- [통합 에이전트 설계 참고 원안](agent-system-reference-draft.md) — 초기 구상 보존 자료
- [리포트 에이전트 설계 참고 원안](report-agent-reference-draft.md) — 최초 요구 보존 자료
- [리포트 에이전트 시스템 설계](report-agent-design.md) — FE·Main·AI 후속 Draft
- [AI 서비스 테스트 전략](test-strategy.md)
- [결정 대기 목록](decisions.md)
- [DEC-002 Python·Grok 모델 결정](DEC-002-python-grok-model.md) — DEC-002 v2 전문
- [CONTRIBUTING](../CONTRIBUTING.md)

## 변경 규칙

- API 요청/응답이 바뀌면 `api-spec.md`, `screen-api-map.md`, OpenAPI를 같은 변경 단위로 갱신합니다.
- 도메인 규칙이나 상태가 바뀌면 `domain-model.md`, `database.md`, 관련 테스트를 함께 갱신합니다.
- FastAPI 계약이 바뀌면 `ai-integration-contract.md`, `agent-system-spec.md`와 Spring 내부 API 계약을 함께 갱신합니다.
- 확정되지 않은 항목에는 날짜와 소유자를 포함한 TBD를 남깁니다.
