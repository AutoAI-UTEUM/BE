# 결정 대기 목록

| 항목 | 내용 |
| --- | --- |
| 상태 | Accepted — DEC-001~028 전 항목 확정 |
| 마지막 갱신 | 2026-07-23 |

확정된 선택은 날짜, 결정자, 이유를 기록하고 관련 문서를 함께 갱신합니다. 마감일은 팀 일정 확정 후 입력합니다.

`DEC-001` 같은 값은 이 문서 안에서 결정을 추적하기 위한 ID이며 GitHub 이슈 번호가 아닙니다. 기본적으로 관련 Epic의 `결정 필요` 체크박스로 관리합니다. 여러 팀의 합의가 필요하거나 실제 개발을 막는 항목만 별도 `[Decision]` 이슈로 만들고, 이 표에 GitHub 이슈 링크를 추가합니다.

> 2026-07-23 기준 전 항목이 Accepted로 확정되었습니다. 원래의 후보/질문 열은 결정 배경 보존을 위해 그대로 두고, 확정 내용을 마지막 열에 기입합니다. DEC-002는 v2로 교체되었습니다(전문: `docs/DEC-002-python-grok-model.md`).

| ID | 결정 항목 | 현재 후보/질문 | 영향 | 소유자 | 목표 시점 | 상태 · 확정 내용 (2026-07-23) |
| --- | --- | --- | --- | --- | --- | --- |
| DEC-001 | Spring Boot 버전 | Java 21 호환 안정 버전 | 전체 Backend | Backend | 프로젝트 생성 전 | **Accepted** — Spring Boot 4.1.x + Java 21 (핵심 의존성 호환 실패 시 4.0.x 하향 후 기록) |
| DEC-002 | Python/Gemini 버전 | Python 버전, 모델명, 지원 기능 | AI 계약/비용 | AI | AI 프로젝트 생성 전 | **Accepted (v2로 교체)** — Python 3.14.x / Grok(xAI) grok-4.5 dated 고정+표류 감지 / reasoning_effort 차등 / 채점 결정성은 구조로 담보 / AgentLlmProfile — 전문: `docs/DEC-002-python-grok-model.md` |
| DEC-003 | Migration 도구 | Flyway vs Liquibase | DB/배포 | Backend | 첫 schema 전 | **Accepted** — Flyway, 빈 baseline부터 |
| DEC-004 | JWT 정책 | access 만료, refresh 저장·회전·폐기 | 보안/FE | Backend+FE | Auth 구현 전 | **Accepted** — access 1시간, refresh 14일 HttpOnly·Secure·SameSite=Lax 쿠키, 회전·재사용 감지·폐기. FE는 access를 메모리 보관(localStorage 금지) |
| DEC-005 | PDF 저장소 | 로컬/오브젝트 스토리지, 인증 다운로드 | Material/Infra | Backend+Infra | 업로드 구현 전 | **Accepted** — 로컬 볼륨 + 저장소 어댑터(storage_key), S3 전환 계획. 파일 접근은 Spring 인증 스트리밍(→S3 시 presigned) |
| DEC-006 | PDF 텍스트 추출 | Spring, Python worker, FastAPI 중 책임 | Material/AI | Backend+AI | 자료 처리 구현 전 | **Accepted** — FastAPI `/internal/ai/extract`가 추출, 저장(material_pages)·상태 전이는 Spring 소유. LLM은 Grok(xAI) |
| DEC-007 | PK/외부 ID | BIGINT vs UUID/별도 public ID | API/DB | Backend | 첫 migration 전 | **Accepted** — BIGINT AUTO_INCREMENT |
| DEC-008 | 페이지 진행 모델 | 세션 단일 pageStatus vs 페이지별 progress | Session/DB | Backend | Session schema 전 | **Accepted** — 세션당 단일 pageStatus (페이지별 진행 엔티티 없음), 페이지 이동 시 초기화 |
| DEC-009 | 퀴즈 재제출 | 1회 제한 vs attempt 관리. 재제출 허용으로 확정할 경우 정답 보호 규칙(제출 후 verdict/정답 공개 시점, 재제출 점수 처리)을 반드시 함께 정의 | Quiz/UX/DB | 전 팀 | Quiz 계약 전 | **Accepted** — 1회 제출 제한 (attempt_no 1 고정) |
| DEC-010 | 통과 기준 | 고정 점수 vs 유형/난이도별 기준 | 진단 흐름 | Product+AI | 채점 구현 전 | **Accepted** — 60% 고정 (env `EDUPILOT_QUIZ_PASS_RATIO`) |
| DEC-011 | 평가 큐 | 최대 개수, 보관/정리 방식 | AI 문맥/DB | Backend+AI | Assessment 구현 전 | **Accepted** — DB 전량 보존. 스냅샷 `quizAssessments`는 세션 스코프 최근 5개. 메모리 승격 판단용은 user×material 교차 세션 최근 20개 (별도 조회 경로) |
| DEC-012 | 메모리 승격 | 반복 횟수/근거/감사 이력 | 개인화 | Product+AI+BE | Memory 구현 전 | **Accepted** — 독립 근거 2회 이상 + confidence ≥ 0.7. candidates 보존이 감사 이력 |
| DEC-013 | 스트리밍 (Accepted) | 전송 방식은 SSE로 **확정** — 아래 확정 기록 참조. 이 표에는 세부 계약(이벤트·취소/재연결·저장 시점)의 잔여 합의만 남음 | FE/BE/AI | 전 팀 | AI 턴 계약 구현 전 | **Accepted** — SSE 기본 Accepted, 세부(이벤트 6종·heartbeat 10s·취소 fetch abort·재연결 재동기화)는 ai-integration-contract v0.4 §5로 확정 |
| DEC-021 | SSE 인증 방식 | EventSource는 Authorization 헤더 불가 — 쿠키 vs 단기 서명 쿼리 토큰 vs fetch 기반 스트림 | 보안/FE/BE | Backend+FE | 스트리밍 구현 전 | **Accepted** — fetch 기반 스트림 (`Authorization: Bearer` 헤더 유지, ReadableStream 파싱). EventSource 미사용 |
| DEC-024 | 활성 세션 재사용 | 같은 자료로 세션 생성 시 기존 ACTIVE 세션 재사용 vs 항상 신규 생성 | Session/UX | Backend+FE | Session 구현 전 | **Accepted** — 동일 자료 세션 생성 시 기존 ACTIVE 재사용(+`reused` 표시), COMPLETED 재개 불가, 메시지 커서 페이지네이션 |
| DEC-025 | 페이지 텍스트 API 노출 | `GET .../pages/{pageNumber}` 운영 노출 여부 — 보안·저작권 검토 | 보안/저작권 | Product+Backend | 자료 API 구현 전 | **Accepted** — 운영 비노출 (dev/디버깅 한정) |
| DEC-027 | CORS 정책 | FE(`localhost:5173`)↔Spring(`8080`) 교차 출처 — 허용 오리진, 자격 증명, SSE 인증(DEC-021)과 연계 | 보안/FE/BE | Backend+FE | FE-BE 연동 전 | **Accepted** — 전역 설정, 오리진 env `EDUPILOT_CORS_ALLOWED_ORIGINS`, allowCredentials=true, 와일드카드 금지 |
| DEC-028 | 회원 탈퇴·자료 삭제 경로 | User/Material의 DELETED(논리 삭제) 상태에 도달하는 기능·API 범위 — 현재는 상태만 정의되고 경로 없음 | 범위/DB | Product+Backend | MVP 범위 확정 시 | **Accepted** — 논리 삭제 + 즉시 익명화(재가입 허용), refresh 전체 폐기, 소유 자료·세션 논리 삭제(퀴즈·평가·메모리는 익명 보존). 자료 삭제는 활성 세션 존재 시 409 거부 |
| DEC-014 | 내부 API 보안 | 사설망, service token, mTLS 등 | 보안/Infra | Backend+AI+Infra | 연동 전 | **Accepted** — `X-Internal-Token` 정적 시크릿 헤더 (env `EDUPILOT_INTERNAL_TOKEN`), FastAPI는 Docker 내부 네트워크에만 바인딩 |
| DEC-015 | API versioning | `/api` vs `/api/v1`, 변경 정책 | 전 클라이언트 | 전 팀 | 첫 외부 API 전 | **Accepted** — base path `/api` (v1 미도입) |
| DEC-016 | 업로드 제한 | 크기, 페이지 수, MIME 검증 | 보안/비용 | Product+Backend | 업로드 구현 전 | **Accepted** — PDF 45MB, 300페이지, MIME/매직바이트 검증 |
| DEC-017 | 관리자 범위 | 자료/사용자 관리 상세 | MVP 범위 | Product | 관리자 구현 전 | **Accepted** — MVP 제외 (상세 TBD) |
| DEC-018 | TEACHER 및 LMS 도메인 | Course/Lecture/Assignment 포함 여부 | 범위/DB | Product | MVP 이후 검토 | **Accepted** — MVP 제외, 이후 검토 |
| DEC-019 | AWS 구성 | EC2/RDS/S3/Nginx/도메인 구성 | 배포/비용 | Infra | dev 배포 전 | **Accepted** — 단일 EC2 + Docker Compose + Nginx HTTPS(certbot), FE 동일 오리진 정적 서빙 |
| DEC-020 | 라이선스 | 오픈소스/비공개 | 배포/공개 | 팀 | 저장소 공개 전 | **Accepted** — 저장소 비공개 유지, 라이선스 파일 없음 |

## 확정된 기본안

### DEC-002 — Python/모델 (v2)

- 상태: Accepted (v2 — v1의 Python/Gemini 안을 대체)
- 결정일: 2026-07-23
- 선택: Python 3.14.x(pydantic v2 전용, 실패 시 3.13 fallback) / 전 에이전트 공통 Grok(xAI) grok-4.5 dated 버전 고정 + 표류 감지(model 필드 assertion) / 용도별 reasoning_effort 차등(Plan·설명·QA=low~medium, 채점·평가·진단=high) / 채점 결정성은 구조로 담보 / `AgentLlmProfile` config
- 전문: [docs/DEC-002-python-grok-model.md](DEC-002-python-grok-model.md)

### DEC-013 — AI 응답 스트리밍 전송 방식

- 상태: Accepted — 세부 계약 포함 확정 (2026-07-23)
- 결정일: 2026-07-10 (SSE 기본), 세부 확정일: 2026-07-23
- 결정자: 프로젝트 담당자
- 선택: Frontend와 Spring 사이의 AI 응답 스트리밍은 SSE를 기본 방식으로 사용한다.
- 이유: 설명·QA처럼 서버에서 클라이언트로 전달되는 단방향 이벤트 스트림에 적합하고 HTTP 기반 인증·중계 구조를 유지할 수 있다.
- 남은 결정(해소됨): 이벤트 schema, heartbeat, `Last-Event-ID` 재연결, 사용자 취소 API, timeout, 최종 메시지 저장 시점, 인증 방식(DEC-021) → **ai-integration-contract v0.4 §5로 확정** — 이벤트 6종(status/thought_summary/content_delta/ui_action/completed/error), heartbeat 10s(SSE comment), 취소=fetch abort, 재연결=`Last-Event-ID` 미지원·FE 재동기화(MVP). 인증은 DEC-021(fetch 기반 스트림)로 확정.
- 대안과 trade-off: WebSocket은 양방향 실시간 통신이 필수로 바뀌는 경우 별도 결정 후 검토한다.
- 후속 변경 문서: [API 명세](api-spec.md) §9에 스트림 URL 초안(`GET /api/sessions/{sessionId}/stream`) 반영, [ai-integration-contract](ai-integration-contract.md) v0.4 §5

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

~~`DEC-011` 평가 큐 정책과 `DEC-012` 학습자 메모리 승격 기준·범위는 계속 Open 상태로 유지한다.~~ → 2026-07-23 확정 — 위 표의 DEC-011·DEC-012 확정 내용 참조.

## 추가 확정 (계약 v0.4에서, DEC 미등재)

- 학습자 메모리 스코프: 수집(후보·평가 윈도우)=세션, 승격된 장기 메모리·digest=user×material
- `REPAIR_FOLLOWUP_QUESTION_SUBMITTED` 이벤트 삭제 — `USER_QUESTION` + `latestRepair` 문맥으로 대체
- 내부 API 타임아웃: turn 180s(첫 이벤트 30s) / grade 90s / assessment·diagnosis 45s / extract 120s (env 관리, extract는 실측 후 조정)
- usage 필드(model, inputTokens, outputTokens, reasoningTokens)를 전 내부 응답 표준 선택 필드로 채택
- 평가 리포트 PDF 출력: 보류(Deferred) — MVP 이후 별도 이슈+DEC

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
