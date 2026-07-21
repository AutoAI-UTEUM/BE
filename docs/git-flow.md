# Git Flow 운영 규칙

| 항목 | 내용 |
| --- | --- |
| 상태 | 팀 합의 초안 |
| 마지막 갱신 | 2026-07-20 |

## 1. 브랜치

| 브랜치 | 목적 | 생성 기준 | 병합 대상 |
| --- | --- | --- | --- |
| `main` | 운영 배포 | 상시 유지 | release/hotfix PR |
| `develop` | 통합 개발 | 상시 유지 | feature PR |
| `feature/*` | 기능 개발 | 최신 develop | develop |
| `release/*` | 배포 준비 | develop | main, 이후 develop 동기화 |
| `hotfix/*` | 운영 긴급 수정 | main | main, 이후 develop 동기화 |

## 2. 보호 규칙

- `main`, `develop` 직접 push 금지
- PR 없이 병합 금지
- 필수 CI 성공 후 병합
- 최소 승인 인원과 merge 방식은 저장소 생성 후 확정
- force push 금지
- 실패한 hook/CI를 우회하지 않고 원인을 수정

## 3. 작업 흐름

1. 최신 기준 브랜치에서 목적별 브랜치를 만듭니다.
2. 한 커밋에 한 주제만 포함합니다.
3. 구현과 관련 테스트·문서를 함께 갱신합니다.
4. PR에 변경 요약과 테스트 방법을 작성합니다.
5. 리뷰와 CI가 완료되면 합의한 merge 방식으로 병합합니다.

간결한 Epic, 필요한 Sub-issue, Project 상태, PR의 `Related to` 표기와 이슈 종료 기준은 [GitHub 이슈·PR 협업 작업 흐름](issue-workflow.md)을 따릅니다.

feature 브랜치 이름은 `feature/{이슈번호}-{짧은-주제}` 형식을 사용합니다(이슈 번호 포함이 표준).

브랜치 예시:

```text
feature/45-auth-login
feature/78-session-page-move
release/0.1.0
hotfix/jwt-validation
```

release/hotfix는 이슈 번호 대신 버전·주제를 사용합니다.

## 4. 커밋 규칙

- 주제별로 분리합니다: 기능, 수정, 리팩터링, 문서, 빌드 등.
- `.env`, credentials, 실제 키, 대용량 생성물을 커밋하지 않습니다.
- 커밋 메시지 언어와 접두사 규칙은 초기 커밋 전에 팀이 확정합니다.
- API/DB 계약 변경은 문서만 또는 코드만 따로 남기지 않고 리뷰 가능한 단위로 묶습니다.

## 5. PR 필수 내용

PR 필수 내용과 템플릿의 단일 기준은 [이슈·PR 협업 작업 흐름](issue-workflow.md) §13입니다. 이 문서에는 별도 목록을 두지 않습니다.

## 6. release/hotfix

- release에서는 기능 추가보다 버전, migration, 문서, 회귀 수정에 집중합니다.
- main 배포 태그/버전 규칙은 첫 배포 전 확정합니다.
- hotfix는 운영 장애의 최소 수정만 포함합니다.
- main 반영 후 develop에도 변경이 누락되지 않도록 PR로 동기화합니다.
