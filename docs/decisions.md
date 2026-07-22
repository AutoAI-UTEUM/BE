# 결정 대기 목록

| 항목 | 내용 |
| --- | --- |
| 상태 | Open |
| 마지막 갱신 | 2026-07-21 |

확정된 선택은 날짜, 결정자, 이유를 기록하고 관련 문서를 함께 갱신합니다. 마감일은 팀 일정 확정 후 입력합니다.

`DEC-001` 같은 값은 이 문서 안에서 결정을 추적하기 위한 ID이며 GitHub 이슈 번호가 아닙니다. 기본적으로 관련 Epic의 `결정 필요` 체크박스로 관리합니다. 여러 팀의 합의가 필요하거나 실제 개발을 막는 항목만 별도 `[Decision]` 이슈로 만들고, 이 표에 GitHub 이슈 링크를 추가합니다.

| ID | 결정 항목 | 현재 후보/질문 | 영향 | 소유자 | 목표 시점 |
| --- | --- | --- | --- | --- | --- |
| DEC-002 | Python 버전·Grok 모델 | LLM provider는 **Grok API(xAI)로 확정**(DEC-006 연계). Python 버전(권장 3.14.x)과 Grok 모델(grok-4.x 계열)·에이전트별 매핑 선택 남음 | AI 계약/비용 | AI | AI 프로젝트 생성 전 |
| DEC-007 | PK/외부 ID | BIGINT vs UUID/별도 public ID | API/DB | Backend | 첫 migration 전 |
| DEC-009 | 퀴즈 재제출 | 1회 제한 vs attempt 관리. 재제출 허용으로 확정할 경우 정답 보호 규칙(제출 후 verdict/정답 공개 시점, 재제출 점수 처리)을 반드시 함께 정의 | Quiz/UX/DB | 전 팀 | Quiz 계약 전 |
| DEC-010 | 통과 기준 | 고정 점수 vs 유형/난이도별 기준 | 진단 흐름 | Product+AI | 채점 구현 전 |
| DEC-011 | 평가 큐 | 최대 개수, 보관/정리 방식 | AI 문맥/DB | Backend+AI | Assessment 구현 전 |
| DEC-012 | 메모리 승격 | 반복 횟수/근거/감사 이력 | 개인화 | Product+AI+BE | Memory 구현 전 |
| DEC-013 | 스트리밍 (Accepted) | 전송 방식은 SSE로 **확정** — 아래 확정 기록 참조. 이 표에는 세부 계약(이벤트·취소/재연결·저장 시점)의 잔여 합의만 남음 | FE/BE/AI | 전 팀 | AI 턴 계약 구현 전 |
| DEC-028 | 회원 탈퇴·자료 삭제 경로 | User/Material의 DELETED(논리 삭제) 상태에 도달하는 기능·API 범위 — 현재는 상태만 정의되고 경로 없음 | 범위/DB | Product+Backend | MVP 범위 확정 시 |
| DEC-015 | API versioning | `/api` vs `/api/v1`, 변경 정책 | 전 클라이언트 | 전 팀 | 첫 외부 API 전 |
| DEC-017 | 관리자 범위 | 자료/사용자 관리 상세 | MVP 범위 | Product | 관리자 구현 전 |
| DEC-018 | TEACHER 및 LMS 도메인 | Course/Lecture/Assignment 포함 여부 | 범위/DB | Product | MVP 이후 검토 |
| DEC-019 | AWS 구성 | EC2/RDS/S3/Nginx/도메인 구성 | 배포/비용 | Infra | dev 배포 전 |
| DEC-020 | 라이선스 | 오픈소스/비공개 | 배포/공개 | 팀 | 저장소 공개 전 |

## 확정된 기본안

### DEC-008 — 페이지 진행 모델

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: MVP는 세션 단일 `pageStatus`를 유지한다. 페이지 이동 시 새 페이지 상태는 `NOT_EXPLAINED`로 초기화하며, 과거 페이지의 설명 원문은 채팅 이력으로 복원한다. 페이지별 이력 모델(`SessionPageProgress`) 분리는 MVP 이후 확장으로 미룬다.
- 이유: 상태 전이·복원 로직이 단순해지고, 재방문 시 "설명할까요?" UI가 다시 떠도 사용자가 거절하면 그만이라 UX 손실이 작다.
- 대안과 trade-off: 페이지별 분리는 방문 이력 보존이 강점이나 테이블·전이 복잡도가 증가한다. 재방문 페이지의 중복 설명은 LLM 비용이 들 수 있어, 재방문 시 기본 선택지를 "아니오"로 두는 UX 보완을 FE와 합의한다.
- 후속 변경 문서: domain-model §4 pageStatus, feature-spec §4, api-spec §5

### DEC-024 — 활성 세션 재사용

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE UX는 계약 리뷰에서 재확인
- 선택: 같은 자료로 `POST /api/sessions` 호출 시 기존 `ACTIVE` 세션이 있으면 새로 만들지 않고 그 세션을 반환한다(재사용). 응답에 `reused` 필드로 구분을 제공한다. "처음부터 다시"는 기존 세션 삭제(`DELETE`) 후 생성으로 해결한다.
- 이유: 자료당 학습 맥락(대화·평가 큐·pageStatus)이 하나로 유지되고 목록에 중복 ACTIVE가 쌓이지 않는다. 목록(SESSION-008)·삭제(SESSION-009) API가 있어 이어하기/새로 시작 UX가 모두 성립한다.
- 대안과 trade-off: 항상 신규 생성은 구현이 단순하나 맥락 분산·목록 혼란을 만든다. 409 거부는 FE 왕복이 늘어난다.
- 부가 확정: 세션 `COMPLETED → ACTIVE` 재개는 MVP에서 불가(완료 세션은 열람만, 재학습은 새 세션). 메시지 조회 페이지네이션은 커서 방식으로 확정.
- 후속 변경 문서: api-spec §5 세션 생성·complete·messages, feature-spec §4, domain-model §4

### DEC-005 — PDF 저장소

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: MVP는 로컬 볼륨(Docker volume) 저장으로 시작하되, 코드가 물리 경로를 알지 못하도록 저장소 어댑터 인터페이스 뒤에 격리하고 DB에는 `storage_key`만 저장한다. FE의 PDF 접근은 Spring의 인증된 다운로드 스트리밍으로 제공한다.
- 이유: 단일 호스트 MVP에서 구현·비용 최소. 어댑터 격리로 이후 전환 비용을 낮춘다.
- 대안과 trade-off: S3는 내구성·presigned URL이 강점이나 AWS 구성(DEC-019) 선행이 필요해 초기 채택을 보류.
- **S3 전환 계획**: AWS 전개(DEC-019 확정) 시 어댑터 구현체를 S3로 교체한다. 이때 FE 다운로드는 Spring이 권한 확인 후 발급하는 **presigned URL**(유효기간 있는 서명 링크, 예: 10분)로 변경해 파일 바이트가 Spring을 거치지 않게 한다. `storage_key` 체계는 전환 시에도 유지한다.
- 후속 변경 문서: api-spec §4 자료 상세, database.md §2, backend-plan §11

### DEC-006 — PDF 텍스트 추출 책임

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) + AI 담당 합의
- 선택: **FastAPI가 추출을 실행하고 Spring이 저장·상태 전이를 소유**한다. 흐름: Spring이 업로드 저장(PROCESSING) → 백그라운드에서 내부 API `POST /internal/ai/extract`로 PDF를 멀티파트 전송 → FastAPI가 페이지별 텍스트를 추출해 배열로 반환 → Spring이 `material_pages` 저장 후 READY/FAILED 전이. LLM provider는 **Grok API(xAI)** 를 사용하며, 에이전트 문맥의 기본 근거는 이 추출 텍스트다(Grok 파일 첨부는 보조 수단으로 AI 담당이 실험 후 결정).
- 이유: Python 추출 생태계를 활용하면서 "FastAPI는 영속 데이터를 직접 만들지 않는다"는 아키텍처 원칙을 유지한다. 추출은 LLM 판단이 없는 결정적 전처리라 하이브리드 원칙(DEC-022)과 충돌하지 않는다. Grok 파일 첨부(attachment_search)는 페이지 단위 문맥 제어가 약해 자체 추출이 설계와 정합.
- 대안과 trade-off: Spring 내 추출(PDFBox)은 경계가 단순하나 팀 결정(Python 측 추출)과 상이. FastAPI 직접 DB 저장은 원칙 위반으로 배제.
- 후속 변경 문서: api-spec §8 내부 API 표, feature-spec §3, Epic3 이슈 구조([AI] 추출 이슈 필수)

### DEC-016 — 업로드 제한

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: 최대 파일 크기 **45MB**, 최대 **300페이지**, Content-Type 확인 + 매직 바이트(`%PDF-`) 검사 + 손상 파일 거부(`INVALID_PDF_FILE`). 크기 초과는 `FILE_TOO_LARGE`(413)이며 Spring multipart 설정과 일치시킨다. 제한값은 환경 변수(`EDUPILOT_UPLOAD_MAX_MB` 등)로 관리한다.
- 이유: 45MB는 Grok 파일 첨부 상한(48MB)보다 작아 원본 첨부 경로를 열어도 안전하고, 서버 메모리·추출 시간을 보호한다.
- 대안과 trade-off: 더 큰 상한은 대용량 강의 자료를 수용하지만 추출·전송 비용이 커진다. 값은 운영 데이터를 보고 조정한다.
- 후속 변경 문서: api-spec §4 업로드, README §6 환경 변수

### DEC-025 — 페이지 텍스트 API 노출

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: `GET /api/materials/{materialId}/pages/{pageNumber}`는 **운영 FE에 노출하지 않는다**. 개발/디버깅 프로파일에서만 활성화하고, 추출 텍스트는 AI 문맥 전용으로 사용한다.
- 이유: 추출 텍스트 무단 유출은 저작권 리스크가 크고, FE가 이 API를 사용하는 화면이 없다(FE는 PDF 원본 뷰어 사용).
- 대안과 trade-off: 운영 노출은 디버깅 편의가 있으나 유출 표면만 넓힌다. 필요 시 관리자 전용으로 재검토.
- 후속 변경 문서: api-spec §2 표·§4, screen-api-map §1, feature-spec §3

### DEC-001 — Spring Boot 버전

- 상태: Accepted (조건부)
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: Spring Boot 4.1.x 최신 패치 + Java 21.
- 이유: 2026-07 기준 최신 안정 버전이며 OSS 지원 기간(2027-07-31)이 가장 길다. 4.0은 2026-12 OSS 지원 종료, 3.5는 이미 종료.
- 대안과 trade-off: 4.0.x는 검증 기간이 길지만 지원 종료가 임박. 초기 세팅에서 핵심 의존성(springdoc-openapi, JJWT 등) 호환 문제가 발생하면 4.0.x 최신 패치로 하향한다.
- 후속 변경 문서: README §4 기술 스택, backend-plan §1

### DEC-003 — Migration 도구

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: Flyway (Community).
- 이유: SQL 파일 기반이라 database.md의 DDL 초안 이전이 쉽고 Spring Boot 통합·학습 곡선이 최소. rollback은 forward-fix 원칙이라 Liquibase의 선언적 rollback 이점이 작다.
- 대안과 trade-off: Liquibase는 DB 독립성이 강점이나 MySQL 고정 프로젝트에서 관리 비용만 추가. Flyway Community의 지원 MySQL 버전 범위는 채택 시 확인한다.
- 후속 변경 문서: README §4, database.md 헤더, backend-plan §4

### DEC-004 — JWT 정책

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE 연동 세부는 구현 전 FE와 재확인
- 선택: access token 만료 1시간(FE 메모리 보관, localStorage 금지) + refresh token 만료 14일(HttpOnly·Secure·SameSite=Lax 쿠키, 회전 + 재사용 감지 시 전체 폐기). 서버는 refresh 해시를 DB에 저장해 로그아웃·강제 폐기를 지원한다.
- 이유: XSS로부터 refresh를 보호하고 탈취 피해를 access 수명(1시간)으로 제한. 쿠키 채택은 DEC-027 CORS credentials 정책과 한 묶음으로 정합.
- 대안과 trade-off: refresh 미도입은 만료 UX가 나쁘고, body 반환·FE 저장은 XSS 노출면이 커진다.
- 후속 변경 문서: api-spec §3 로그인 응답, requirements AUTH-005, error-code 갱신 흐름

### DEC-014 — 내부 API 인증

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: 네트워크 격리 + 정적 service token 2중 방어. FastAPI는 Docker 내부 네트워크에만 바인딩하고, Spring은 모든 내부 호출에 `X-Internal-Token`(환경 변수 `EDUPILOT_INTERNAL_TOKEN` 주입) 헤더를 첨부하며 FastAPI가 검증한다.
- 이유: 단일 호스트 Docker Compose 규모에서 충분한 방어이며 구현 부담이 작다.
- 대안과 trade-off: mTLS는 인증서 운영 부담이 MVP에 과함 — 다중 호스트 전개 시 재검토. 무인증은 설정 실수 한 번에 내부 API 위조가 가능해 배제.
- 후속 변경 문서: api-spec §8 내부 API 필수 정책, README §6 환경 변수

### DEC-021 — SSE 인증 방식

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE 구현 방식은 구현 전 FE와 재확인
- 선택: fetch 기반 스트림. FE는 EventSource 대신 `Accept: text/event-stream`으로 fetch를 호출해 ReadableStream을 파싱하고, 기존 `Authorization: Bearer` 헤더를 그대로 사용한다. 재연결·`Last-Event-ID`는 FE가 처리한다(fetch-event-source 패턴).
- 이유: 기존 Bearer 인증 체계를 재사용해 추가 서버 작업(쿼리 토큰 발급 등)이 불필요하고, access 토큰 메모리 보관 정책(DEC-004)과 정합.
- 대안과 trade-off: 단기 서명 쿼리 토큰은 EventSource 자동 재연결을 살리지만 발급 API·URL 노출 마스킹이 추가된다. 쿠키 인증은 권한 모델이 꼬인다.
- 후속 변경 문서: api-spec §9 SSE 계약, screen-api-map §5

### DEC-027 — CORS 정책

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — 운영 오리진은 배포 도메인 확정 시 추가
- 선택: Spring 전역 `CorsConfigurationSource` 단일 설정. 허용 오리진은 환경 변수 주입(local `http://localhost:5173`, 와일드카드 금지), 메서드 GET/POST/PATCH/DELETE/OPTIONS, 헤더 `Authorization`·`Content-Type`, `allowCredentials=true`(DEC-004 refresh 쿠키 채택), preflight 캐시 3600초.
- 이유: 컨트롤러별 `@CrossOrigin` 산재를 막고 환경별 오리진을 설정으로 관리. credentials 사용 시 명시 오리진이 필수라 와일드카드를 금지한다.
- 대안과 trade-off: 오리진 와일드카드는 설정이 쉽지만 credentials와 병용 불가·보안상 부적합.
- 후속 변경 문서: backend-plan §2, README §6 환경 변수

### DEC-013 — AI 응답 스트리밍 전송 방식

- 상태: Accepted — 세부 계약은 Open
- 결정일: 2026-07-10
- 결정자: 프로젝트 담당자
- 선택: Frontend와 Spring 사이의 AI 응답 스트리밍은 SSE를 기본 방식으로 사용한다.
- 이유: 설명·QA처럼 서버에서 클라이언트로 전달되는 단방향 이벤트 스트림에 적합하고 HTTP 기반 인증·중계 구조를 유지할 수 있다.
- 남은 결정: 이벤트 schema, heartbeat, `Last-Event-ID` 재연결, 사용자 취소 API, timeout, 최종 메시지 저장 시점, 인증 방식(DEC-021)
- 대안과 trade-off: WebSocket은 양방향 실시간 통신이 필수로 바뀌는 경우 별도 결정 후 검토한다.
- 후속 변경 문서: [API 명세](api-spec.md) §9에 스트림 URL 초안(`GET /api/sessions/{sessionId}/stream`) 반영

### DEC-022 — AI 호출 주체 원칙 (하이브리드)

- 상태: Accepted
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: 자유 학습 턴(질문, 설명, 퀴즈 유형 선택, 진단 답변, 교정 후 질문)은 `/internal/ai/turn` 단일 진입점으로 전달하고 에이전트 선택은 FastAPI Orchestrator가 담당한다. 퀴즈 제출 후의 결정적 파이프라인([SHORT/ESSAY만] 채점 → 내부 평가 → 저득점 시 진단)만 Spring이 전용 내부 API(`grade`, `quiz-assessment`, `diagnosis`)를 이벤트 타입·점수 기준 규칙으로 순차 호출한다. 오개념 교정과 메모리 후보·승격 전용 엔드포인트는 두지 않고 turn 도구로 흡수한다.
- 이유: 에이전트 명세서의 시나리오(9.5, 9.6, 9.8)가 후처리를 이미 결정적 순서로 기술하고 있고, 채점·평가의 재현성 요구에는 LLM 계획 단계가 불필요하다. 메모리 승격·교정 선택처럼 LLM 판단이 본질인 기능은 turn에 남겨 Orchestrator 거버넌스를 보존한다.
- 대안과 trade-off: turn 단일 통일은 Plan·Policy 체계를 온전히 유지하지만 제출마다 LLM 계획 비용·지연이 발생하고 채점 재현성과 긴장한다. 전용 엔드포인트 중심은 지연이 최소지만 Orchestrator·메모리 거버넌스가 형해화된다.
- 후속 변경 문서: README 핵심 원칙, [시스템 아키텍처](architecture.md) §2, [API 명세](api-spec.md) §8, [에이전트 시스템 명세](agent-system-spec.md) §6·§7

### DEC-023 — 학습자 메모리 조회 스코프

- 상태: Accepted
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: `GET /api/users/me/memory`는 `materialId` 쿼리 파라미터를 필수로 받아 자료 스코프의 메모리 요약을 반환한다.
- 이유: 저장 모델이 `learner_memories` `UK(user_id, material_id)`로 자료별이므로 조회 API도 같은 스코프를 가져야 한다.
- 대안과 trade-off: 전 자료 목록 반환은 화면 요구가 확정되지 않았고 응답 비대화 우려가 있어 보류. 자료 범위를 넘어선 전역 프로필은 별도 검토(DEC-012 연계).
- 후속 변경 문서: [API 명세](api-spec.md) §7, [화면-API 매핑](screen-api-map.md), README API 초안

### DEC-026 — 자료 접근 모델 (소유자 전용)

- 상태: Accepted
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: MVP에서 학습 자료는 업로드한 본인만 조회·학습할 수 있다. "접근 가능한 자료"는 "본인이 업로드한 자료"를 의미한다.
- 이유: 타인 PDF 노출에 따른 저작권·보안 리스크를 차단하고, issues/02의 "타인 자료 접근 차단" 테스트 기준과 정합하다. 인가 검증과 목록 쿼리 구현이 단순해진다.
- 대안과 trade-off: 전체 공유 모델은 콘텐츠 풀이 풍부해지지만 저작권 검토·공유 권한 모델이 선행돼야 하므로 MVP 이후 검토(DEC-017 관리자 범위와 연계).
- 후속 변경 문서: [요구사항 명세](requirements.md) §1·MATERIAL-002, [API 명세](api-spec.md) §2 권한 열

`DEC-011` 평가 큐 정책과 `DEC-012` 학습자 메모리 승격 기준·범위는 계속 Open 상태로 유지한다.

## 결정 기록 형식

```markdown
### DEC-XXX — 제목

- 상태: Accepted / Superseded
- 결정일: YYYY-MM-DD
- 결정자: ...
- 선택: ...
- 이유: ...
- 대안과 trade-off: ...
- 후속 변경 문서: ...
```
