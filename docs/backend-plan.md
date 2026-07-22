# Spring 백엔드 실행 계획

| 항목 | 내용 |
| --- | --- |
| 상태 | 구현 전 계획 |
| 마지막 갱신 | 2026-07-21 |
| 담당 | Spring Backend |

이 문서는 작업 순서를 정의할 뿐 현재 프로젝트 생성을 지시하지 않습니다. 각 단계의 미확정 결정을 먼저 합의한 뒤 구현합니다.

## 0. 계약 기준선 확정

작업:

- 요구사항, 기능 흐름, 화면-API 매핑 검토
- Spring/FastAPI 책임과 내부 API 계약 합의
- MVP 제외 범위 확인
- 미확정 기술 선택 결정

검증:

- FE/BE/AI 담당자가 같은 이벤트·상태·응답 용어를 사용합니다.
- [결정 대기 목록](decisions.md)의 구현 차단 항목이 해소됩니다.

## 1. 기술 스택 확정

작업:

- Java 21과 Spring Boot 세부 버전
- MySQL, JPA
- JWT access/refresh 정책
- Flyway 또는 Liquibase
- 테스트 보조 도구, Testcontainers 여부
- Docker, GitHub Actions, AWS 세부 구성

검증:

- 버전·선택 이유·지원 종료 일정을 문서에 기록합니다.
- 로컬과 CI에서 동일한 빌드 도구/Java 버전을 사용합니다.

## 2. 프로젝트 초기 세팅

작업:

- Spring Boot 프로젝트 생성
- 패키지 구조와 의존 방향 정의
- `local`, `dev`, `prod` 환경 분리
- 비밀값 주입 방식과 예제 환경 파일
- 공통 성공/에러 응답, 예외 처리, 추적 ID
- health check

검증:

- 비밀값 없이 저장소가 빌드됩니다.
- local profile로 앱과 health check가 실행됩니다.
- 예외 테스트가 공통 오류 계약을 검증합니다.

## 3. Git/CI 기본 세팅

작업:

- 브랜치 보호 규칙
- PR/Issue 템플릿
- GitHub Actions 테스트·빌드
- 의존성 캐시와 테스트 리포트

검증:

- 깨진 테스트/빌드가 PR을 차단합니다.
- `main`, `develop` 직접 push가 차단됩니다.

## 4. DB 설계와 migration

작업:

- ERD와 테이블/제약/인덱스 확정
- 첫 migration
- 로컬용 최소 seed 여부 결정
- 정답 데이터와 개인정보 보호 검토

검증:

- 빈 DB에 migration이 재현됩니다.
- 핵심 유일성/외래키/점수 제약이 테스트됩니다.
- rollback 또는 forward-fix 절차를 문서화합니다.

## 5. 인증/인가

작업:

- 회원가입, 로그인, 내 정보
- 비밀번호 해시와 JWT 검증
- 역할과 리소스 소유권 검사
- 인증 실패/권한 실패 응답

검증:

- 정상, 만료, 위조, 권한 부족, 타인 리소스 접근 테스트가 통과합니다.
- 비밀번호/JWT가 로그에 남지 않습니다.

## 6. 핵심 도메인 구현

권장 순서:

1. User/Auth
2. LearningMaterial/MaterialPage
3. LearningSession/ChatMessage/StateReducer
4. QaThread
5. Quiz/QuizSubmission 및 MCQ/OX 채점
6. QuizAssessment/Diagnosis/RepairResult
7. LearnerMemory
8. FastAPI AiClient와 턴 오케스트레이션 경계

각 도메인은 `계약·테스트 → Service → Repository/Adapter → Controller` 순으로 검증합니다. 단순 CRUD에 동일한 테스트를 과도하게 반복하지 않고, 소유권과 핵심 규칙을 우선합니다.

## 7. API 계약 우선 개발

작업:

- request/response/error를 FE와 합의
- springdoc-openapi 반영
- FE 병렬 개발용 mock/stub 제공 여부 결정
- API 변경 공지 방식 합의

검증:

- [요구사항 명세](requirements.md)의 Must 요구사항과 연결된 [화면-API 매핑](screen-api-map.md)의 모든 화면 흐름에 계약이 있습니다. (화면 단위 우선순위 열은 별도로 두지 않고 요구사항 우선순위를 기준으로 판정합니다.)
- Swagger 예시와 실제 테스트 응답이 일치합니다.

## 8. FastAPI 연동

작업:

- 내부 API DTO와 `schemaVersion`
- timeout, 제한된 재시도, circuit breaker 필요성 검토
- AI 오류 매핑
- `turnId`/`requestId` 멱등성
- SSE endpoint, 이벤트 schema, heartbeat, 취소·재연결 정책

검증:

- 정상, timeout, 5xx, 잘못된 JSON, 알 수 없는 statePatch 테스트가 통과합니다.
- AI 성공 후 DB 실패, 클라이언트 재전송 시 중복 기록이 생기지 않는 방안을 검증합니다.

## 9. 테스트 기준

- Service 단위 테스트: 상태 전이, 채점, 권한, 메모리 승격 규칙
- Controller 테스트: 요청 검증, 인증/인가, 오류 계약
- Repository 테스트: 복잡한 쿼리와 제약조건만 선별
- 통합 테스트: 인증 → 세션 → 턴 → 퀴즈 제출 핵심 흐름
- FastAPI 연동 테스트: mock/stub 기반 계약 테스트
- 실제 Grok 호출은 기본 CI에서 제외

검증:

- Must 시나리오와 예외가 테스트 매트릭스에 연결됩니다.
- 테스트가 외부 AI 가용성에 의존하지 않습니다.

## 10. 로깅/모니터링

작업:

- 요청/오류/외부 호출 추적 ID
- 환경별 로그 레벨
- 민감정보 마스킹
- health/readiness와 핵심 메트릭

검증:

- 한 사용자 요청을 Spring-FastAPI 구간에서 추적할 수 있습니다.
- 비밀번호, 토큰, API Key, 전체 PDF/답안 원문이 기본 로그에 없습니다.

## 11. 배포 준비와 운영

작업:

- Spring Dockerfile, 로컬 Docker Compose
- dev/prod 환경 변수 문서
- dev 배포와 smoke test
- migration 순서, 롤백, 장애 대응, 배포 체크리스트
- API versioning 기준

검증:

- 새 환경에서 문서만으로 배포를 재현할 수 있습니다.
- health check와 핵심 smoke test가 성공합니다.
- 이전 버전으로 복귀할 수 있는 절차가 검증됩니다.
