# [Platform] Docker·CI/CD·dev/prod 배포 및 운영 기준

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [운영 Epic 초안](epics/08-operations.md)을 사용합니다.

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
area: infra
area: docs
type: chore
```

## 목표

Frontend, Main Service, AI Service, MySQL을 로컬에서 재현 가능하게 실행하고 dev/prod 환경에 안전하게 배포·검증·롤백할 수 있는 운영 기준을 만든다.

## 연결 요구사항

- `OPS-002` 환경 분리·비밀값 관리
- `OPS-003` CI 테스트·빌드
- `OPS-004` health check
- `OPS-006` Dockerfile·Docker Compose
- `OPS-007` 배포·롤백·migration 절차

## 범위

### 포함

- Main Service Dockerfile
- AI Service Dockerfile
- Frontend 배포 산출물/컨테이너 방식 합의
- MySQL 포함 로컬 Docker Compose
- GitHub Actions CI와 dev 배포 파이프라인
- 환경 변수/Secrets 주입
- DB migration 배포 순서
- health/readiness와 smoke test
- 배포 체크리스트와 rollback 절차
- dev/prod 구성 분리
- 초기 AWS 구성

### 제외

- 필요성이 검증되지 않은 Kubernetes/ECS 전환
- 자동 확장·멀티 리전
- 복잡한 무중단 migration 자동화

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Decision]` AWS 서비스·DB·파일 저장소·reverse proxy 구성 확정
- `[Infra]` Main Service Dockerfile
- `[Infra]` AI Service Dockerfile
- `[FE/Infra]` Frontend 빌드·정적 배포 방식
- `[Infra]` MySQL 포함 로컬 Docker Compose
- `[CI]` Backend/AI/FE 빌드·테스트 파이프라인
- `[CD]` dev 배포와 smoke test
- `[DB]` migration 적용·실패·forward-fix 절차
- `[Security]` GitHub Secrets와 운영 비밀값 관리
- `[Docs]` 배포·롤백·장애 대응 체크리스트
- `[Integration]` 새 환경에서 전체 시작·health·핵심 smoke test

## 예정 로컬 구성

```text
Frontend:          http://localhost:5173
Main Service:      http://localhost:8080
AI Service:        http://localhost:8000
MySQL:             localhost:3306
```

## 선행 의존성

- [프로젝트·CI 기반](00-foundation.md)
- [health·로그·추적 기준](11-observability.md)
- 배포 대상 MVP 부모 이슈
- `DEC-005` 파일 저장소
- `DEC-019` AWS 구성

## 주요 예외·검증

- 환경 변수/Secret 누락
- 이미지 빌드와 로컬 실행 환경 불일치
- DB migration 실패
- Main Service 배포 후 AI Service 계약 불일치
- health endpoint는 성공하지만 실제 DB/AI 연동 실패
- 롤백 시 이전 애플리케이션과 새 schema 비호환
- 로그에 운영 비밀값 노출

## 완료 조건

- [ ] Dockerfile과 Compose가 새 환경에서 재현된다.
- [ ] CI가 Main/AI/FE 테스트와 빌드를 검증한다.
- [ ] dev 환경에 자동 또는 문서화된 방식으로 배포할 수 있다.
- [ ] 환경 변수와 Secrets 목록이 실제 값 없이 문서화됐다.
- [ ] migration 적용·실패·복구 절차가 있다.
- [ ] health/readiness와 핵심 smoke test가 통과한다.
- [ ] rollback 또는 forward-fix 절차를 수행할 수 있다.
- [ ] prod 배포 체크리스트와 책임자가 정해졌다.
