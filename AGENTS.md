# EduPilot Codex 지침

## 프로젝트 상태

- EduPilot은 PDF 강의 자료를 기반으로 설명, 질의응답, 퀴즈, 채점, 오개념 교정을 제공하는 AI 학습 튜터다.
- 현재는 **구현 시작 전 문서화 단계**다.
- 사용자가 명시적으로 요청하기 전에는 Spring/FastAPI/Frontend 프로젝트를 생성하거나 의존성을 설치하지 않는다.
- 확정되지 않은 기술과 정책은 임의로 결정하지 말고 `docs/decisions.md`의 TBD로 관리한다.

## 작업 전 읽을 문서

작업 범위에 필요한 문서만 우선 읽는다.

- 전체 안내와 문서 목록: `README.md`, `docs/README.md`
- 제품 범위: `docs/project-goals.md`, `docs/requirements.md`
- 기능 흐름: `docs/feature-spec.md`
- 아키텍처와 책임 경계: `docs/architecture.md`
- API 작업: `docs/api-spec.md`, `docs/screen-api-map.md`, `docs/error-code.md`
- 도메인/DB 작업: `docs/domain-model.md`, `docs/database.md`
- Spring 작업 순서/규칙: `docs/backend-plan.md`, `docs/backend-convention.md`
- FastAPI/AI 연동: `docs/agent-system-spec.md`
- 협업/완료 기준: `CONTRIBUTING.md`, `docs/git-flow.md`, `docs/issue-workflow.md`, `docs/issues/README.md`, `docs/issue-plan.md`, `docs/definition-of-done.md`

문서가 충돌하면 다음 순서를 따른다.

1. 팀에서 승인한 결정, 실제 OpenAPI, 적용된 DB migration
2. `docs/architecture.md`, `docs/api-spec.md`
3. `docs/backend-plan.md`
4. `docs/agent-system-spec.md`의 FastAPI 내부 참고 설계

## 시스템 책임 경계

- Frontend는 Spring 외부 API만 호출하며 FastAPI를 직접 호출하지 않는다.
- Spring Backend와 MySQL이 인증, 권한, 영속 데이터, 세션 상태의 기준이다.
- FastAPI는 AI 문맥 구성, 계획, 정책 검증, 에이전트 실행을 담당한다.
- FastAPI의 `statePatch`는 제안이며 Spring이 허용된 상태 전이인지 검증한 후 저장한다.
- 페이지 이동과 MCQ/OX 채점은 Spring이 결정적으로 처리한다.
- SHORT/ESSAY 채점은 FastAPI GraderAgent 결과를 Spring이 검증해 저장한다.
- 원안의 `JsonStore`를 운영 영속 저장소로 구현하지 않는다.

## MVP 범위

포함:

- Auth/User
- LearningMaterial/MaterialPage
- LearningSession/ChatMessage/QaThread
- AI 설명·QA 응답 스트리밍
- 네 가지 퀴즈 유형과 제출·채점
- QuizAssessment, Diagnosis, RepairResult
- 반복 근거 기반 LearnerMemory

미확정 또는 MVP 이후:

- `TEACHER` 권한
- Course, Lecture, Assignment, Notification
- 결제, 화상 강의, 교사-학생 실시간 채팅
- 복잡한 관리자 통계

이 범위를 사용자의 승인 없이 선행 구현하지 않는다.

## 구현 원칙

- 요청을 검증 가능한 작은 목표로 나누고 최소 변경만 한다.
- 관련 없는 리팩터링, 포맷 변경, 선행 추상화를 섞지 않는다.
- 엔티티를 API 요청/응답으로 직접 노출하지 않는다.
- 모든 세션·퀴즈·진단 접근에서 역할과 리소스 소유권을 검증한다.
- 퀴즈 정답과 루브릭은 제출 전에 Frontend 응답에 포함하지 않는다.
- 외부 AI 호출 중 DB 트랜잭션을 오래 유지하지 않는다.
- AI 응답, 상태 패치, 문항 ID, 점수 범위와 합계를 신뢰하지 말고 검증한다.
- 단일 질문이나 퀴즈 결과만으로 장기 학습자 메모리를 확정하지 않는다.
- 비밀번호, JWT, API Key, 실제 `.env`, 전체 PDF/학생 답안 원문을 로그에 남기지 않는다.

## API와 문서 동기화

- API request/response/error 변경 시 OpenAPI, `docs/api-spec.md`, `docs/screen-api-map.md`를 함께 갱신한다.
- 에러 코드 변경 시 `docs/error-code.md`를 함께 갱신한다.
- 도메인 상태나 규칙 변경 시 `docs/domain-model.md`와 관련 테스트를 갱신한다.
- DB 변경은 새 migration과 `docs/database.md`를 함께 갱신한다.
- Spring-FastAPI 계약 변경 시 `docs/agent-system-spec.md`와 내부 API DTO를 함께 갱신한다.
- 구현되지 않은 기능을 README나 문서에서 완료된 것처럼 표현하지 않는다.

## 테스트와 검증

- 핵심 상태 전이, 채점, 권한, 소유권, 메모리 승격 규칙은 테스트한다.
- Controller 테스트는 요청 검증·인증·응답 계약을 검증한다.
- FastAPI 연동은 실제 Grok 없이 mock/stub 계약 테스트가 가능해야 한다.
- AI timeout, 잘못된 JSON, 알 수 없는 action/statePatch, 중복 요청을 검증한다.
- 단순 CRUD와 프레임워크 동작을 의미 없이 반복 테스트하지 않는다.
- 구체적인 빌드/테스트 명령은 프로젝트가 생성된 후 실제 명령만 이 파일에 추가한다.

## Git 및 변경 규칙

- 사용자가 요청하지 않으면 커밋하거나 push하지 않는다.
- 한 커밋에는 한 주제만 포함한다.
- `main`, `develop` 직접 push와 force push를 하지 않는다.
- `.env`, credentials, 실제 키, 생성 산출물을 stage하지 않는다.
- API/DB/설정 변경은 PR 본문에 영향과 검증 방법을 기록한다.

## 완료 기준

- 변경이 요구사항과 책임 경계를 지킨다.
- 정상 흐름과 주요 실패 흐름을 검증한다.
- 관련 문서와 계약이 실제 변경과 일치한다.
- 민감정보와 비공개 정답 데이터가 노출되지 않는다.
- `docs/definition-of-done.md`의 해당 항목을 충족한다.
