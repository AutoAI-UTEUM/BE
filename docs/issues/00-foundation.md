# [Foundation] 프로젝트 기반 및 공통 계약 기준선 구축

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [간결한 Epic 초안](epics/01-foundation-contract.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: main-service
area: ai-service
area: integration
area: infra
area: docs
type: chore
```

## 목표

Main Service와 AI Service가 같은 Backend 저장소에서 독립적으로 빌드·테스트되고, 문서화된 공통 응답·오류·내부 API 계약을 기준으로 연동 개발을 시작할 수 있는 기반을 만든다.

## 연결 요구사항

- `OPS-001` OpenAPI
- `OPS-002` 환경 분리와 비밀값 관리
- `OPS-003` CI 테스트·빌드
- `OPS-004` health check

## 범위

### 포함

- Backend 저장소 기본 디렉터리: `main-service`, `ai-service`
- Java 21/Spring Boot 프로젝트 초기 설정
- Python/FastAPI 프로젝트 초기 설정
- `local`, `dev`, `prod` 설정 분리
- 공통 성공·오류 응답과 trace ID
- Spring 외부 API 및 FastAPI 내부 API의 버전 기준
- Main/AI health check
- 테스트·빌드 CI
- PR/Issue template와 branch protection 준비
- 로컬 실행에 필요한 예제 환경 변수 문서

### 제외

- 실제 사용자 기능 구현
- 운영 AWS 배포
- 실제 Gemini 기반 학습 응답 구현
- Frontend 상세 화면 구현

## 작업 후보 — 필요할 때만 Sub-issue 생성

- 공통 기술 결정 확정 — 실제 차단 시에만 별도 `[Decision]` 이슈 생성
- `[Main]` Spring 프로젝트와 local/dev/prod profile 구성
- `[Main]` 공통 응답·예외·trace ID 기반 구성
- `[AI]` FastAPI 프로젝트와 환경 설정 구성
- `[AI]` 표준 AI 오류/응답 envelope 구성
- `[Contract]` Spring 외부/내부 API 버전과 JSON naming 합의
- `[Integration]` Main ↔ AI health/contract stub 호출 검증
- `[Infra]` Backend 테스트·빌드 GitHub Actions 구성
- `[Docs]` 실행 방법, 환경 변수, 비밀값 관리 문서화

## 계약 기준

- Frontend는 Spring 외부 API만 호출한다.
- Spring과 MySQL이 영속 데이터와 세션 상태의 기준이다.
- FastAPI는 AI 계획·생성 결과를 구조화해 반환한다.
- 내부 계약에는 `schemaVersion`, `turnId`, `traceId`를 검토한다.
- 비밀값은 저장소에 커밋하지 않는다.

## 선행 결정

- `DEC-001` Spring Boot 버전
- `DEC-002` Python/Gemini 버전
- `DEC-003` Flyway/Liquibase
- `DEC-007` 식별자 전략
- `DEC-014` 내부 API 보안
- `DEC-015` API versioning

## 주요 예외·검증

- 잘못된 profile/환경 변수 누락 시 빠르게 실패
- 비밀값이 로그에 노출되지 않음
- Main/AI 중 하나의 빌드 실패가 CI에서 검출됨
- FastAPI 장애를 Spring의 표준 오류로 매핑 가능
- 빈 DB에서 migration 재현 가능

## 완료 조건

- [ ] Main Service가 합의한 명령으로 빌드·테스트된다.
- [ ] AI Service가 합의한 명령으로 테스트된다.
- [ ] local/dev/prod 설정과 환경 변수 예시가 문서화됐다.
- [ ] 공통 성공/오류 응답 계약이 승인됐다.
- [ ] Spring이 AI Service health 또는 stub endpoint를 호출할 수 있다.
- [ ] CI가 두 서비스 테스트와 빌드 실패를 차단한다.
- [ ] OpenAPI/Swagger 접근 경로가 정해졌다.
- [ ] 실제 비밀값이 저장소와 로그에 없다.
- [ ] 관련 문서와 `AGENTS.md`에 실제 명령이 반영됐다.
