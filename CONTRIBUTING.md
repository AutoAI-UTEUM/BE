# EduPilot 협업 가이드

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-07-20 |

## 1. 시작 전 확인

1. [요구사항](docs/requirements.md)과 [기능 명세](docs/feature-spec.md)를 확인합니다.
2. FE/BE 계약 작업이면 [화면-API 매핑](docs/screen-api-map.md)과 [API 명세](docs/api-spec.md)를 확인합니다.
3. 미확정 사항은 [결정 대기 목록](docs/decisions.md)에 먼저 기록하고 임의로 확정하지 않습니다.
4. 한 작업은 하나의 목적과 검증 기준을 갖도록 작게 나눕니다.

## 2. 브랜치 전략

- `main`: 운영 배포
- `develop`: 통합 개발
- `feature/*`: 기능 개발
- `release/*`: 배포 준비
- `hotfix/*`: 운영 긴급 수정

`main`, `develop` 직접 push를 금지하고 PR을 통해 병합합니다. 자세한 규칙은 [Git Flow](docs/git-flow.md)를 따릅니다.

여러 파트가 연결되는 기능은 Backend 저장소에 간결한 Epic을 만듭니다. 담당자·저장소·PR·차단 상태를 독립적으로 추적해야 하는 작업만 Sub-issue로 나눕니다. 이슈 위치, Project 필드, 라벨, 템플릿, PR 연결 및 종료 기준은 [GitHub 이슈·PR 협업 작업 흐름](docs/issue-workflow.md)을 따릅니다.

프로젝트 기능별 부모 이슈를 처음 만들 때는 [8개 GitHub Epic 초안](docs/issues/README.md)에서 해당 문서를 복사합니다. 상세 사용자 흐름·예외·계약 검토는 [상세 작업 분해 계획](docs/issue-plan.md)을 참고하며 Epic 본문에 전부 복사하지 않습니다.

## 3. 커밋

- 한 커밋에는 한 주제만 포함합니다.
- 비밀값, `.env`, 자격 증명, 빌드 산출물을 포함하지 않습니다.
- 관련 없는 리팩터링이나 포맷 변경을 섞지 않습니다.
- 프로젝트 커밋 메시지 스타일은 첫 합의 후 이 문서에 예시를 추가합니다.

## 4. Pull Request

PR 필수 내용과 템플릿의 단일 기준은 [GitHub 이슈·PR 협업 작업 흐름](docs/issue-workflow.md) §13입니다. 이 문서에는 별도 목록을 두지 않습니다.

API 변경은 OpenAPI, `docs/api-spec.md`, `docs/screen-api-map.md`를 함께 갱신합니다. DB 변경은 migration과 `docs/database.md`를 함께 갱신합니다.

`develop` 대상 PR은 기본적으로 `Related to`로 작업 이슈와 부모 이슈를 참조합니다. 자동 종료 키워드는 default branch 대상 PR이 이슈 전체를 실제로 완료하는 경우에만 사용합니다.

## 5. 리뷰 기준

- 요구사항과 범위를 정확히 충족하는가?
- Spring, FastAPI, FE 책임 경계를 지키는가?
- 인증, 소유권, 입력 검증, 정답 비노출이 보장되는가?
- 실패나 중복 요청이 상태를 손상시키지 않는가?
- 핵심 비즈니스 규칙에 테스트가 있는가?
- 로그와 문서에 민감정보가 남지 않는가?
- 문서와 실제 계약이 일치하는가?

## 6. 완료 기준

기능을 완료로 표시하기 전에 [Definition of Done](docs/definition-of-done.md)을 확인합니다.
