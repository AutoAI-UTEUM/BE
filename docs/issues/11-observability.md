# [Platform] 요청·오류·AI 호출 로깅 및 모니터링

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [운영 Epic 초안](epics/08-operations.md)을 사용합니다.

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

한 사용자 요청을 Frontend → Main Service → AI Service 구간에서 추적하고 장애 원인을 확인할 수 있게 하되, 토큰·비밀번호·PDF/답안 원문 등 민감정보는 로그에 남기지 않는다.

## 연결 요구사항

- `OPS-004` health check
- `OPS-005` 요청·오류·외부 AI 호출 로그
- 공통 오류의 `traceId`

## 범위

### 포함

- traceId/requestId/turnId 전달
- Main Service 구조화 요청·오류 로그
- AI Service 구조화 호출·오류 로그
- Gemini 호출 시간·성공/실패 분류
- 환경별 로그 레벨
- 민감정보 마스킹
- health/readiness 기본 상태
- 핵심 메트릭과 알림 후보
- 장애 조사와 로그 검색 절차 문서

### 제외

- 사용자 대화/답안 원문을 기본 운영 로그에 저장
- 특정 상용 APM 도구 확정 전 벤더 종속 구현
- 복잡한 학습 분석 대시보드

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` 공통 추적 ID와 로그 필드 기준
- `[Main]` 요청·오류·외부 AI 호출 구조화 로그
- `[AI]` agent/tool/Gemini 호출 구조화 로그
- `[Security]` 민감정보 마스킹·로그 접근 정책
- `[Infra]` 로그 수집·보관·메트릭·알림 도구 결정
- `[Integration]` 단일 요청의 end-to-end trace 검증
- `[Docs]` 로그 검색·장애 조사·보관 정책 문서

## 공통 로그 필드 후보

```text
timestamp
level
service
environment
traceId
requestId
turnId
sessionId
endpoint/tool
status
durationMs
errorCode
```

## 로그 금지 정보

- 비밀번호와 password hash
- JWT, refresh token, API Key
- 실제 `.env` 값
- 전체 PDF 텍스트
- 전체 학생 답안·대화 원문
- 퀴즈 비공개 정답·루브릭
- 내부 chain-of-thought

## 선행 의존성

- [공통 trace/error 기반](00-foundation.md)
- AI 연동 기능의 turnId/actionId 계약
- 로그/메트릭 도구 결정은 배포 환경과 함께 진행 가능

## 주요 예외·검증

- Main과 AI traceId 불일치
- 동일 키를 서비스마다 다른 의미로 사용
- 예외 stack에 토큰/프롬프트 원문 포함
- 운영에서 debug 로그 상시 활성화
- 로그 보관 기간과 접근 권한 미정
- health는 성공하지만 핵심 의존성은 사용 불가

## 확정 계약 (Epic 8 ⓐ) — 추적 ID·로그 필드·마스킹·health

> 아래 내용이 ⓐ [Contract]의 확정안입니다. BE(한승준)·AI(고영빈)·FE(이감) 승인 후 ⓑ·ⓒ 구현의 기준이 됩니다.

### 1. 추적 ID 체계

| ID | 생성 주체 | 전달 | 로그 표기 |
| --- | --- | --- | --- |
| `traceId` | Spring 진입 필터 — 요청에 `X-Trace-Id` 헤더가 있으면 재사용, 없으면 UUID 생성 | Spring→FastAPI 호출 시 `X-Trace-Id` 헤더로 전파, 모든 응답 헤더에 `X-Trace-Id` 포함 | 모든 로그 라인 필수 |
| `requestId` | 클라이언트 (턴 요청 멱등 키 — api-spec §5) | 요청 body | 턴 처리 로그에 포함 |
| `turnId` / `actionId` | Spring / AI Service (ai-integration-contract) | 내부 API body | AI 호출 로그에 포함 |

- 공통 오류 응답의 `traceId` 필드는 로그의 `traceId`와 동일한 값이다 (error-code.md 정합). FE는 오류 화면에서 이 값을 노출해 문의 시 전달할 수 있다.

### 2. 공통 로그 필드 (JSON 구조화)

- 필수: `timestamp`(ISO-8601, 타임존 포함), `level`, `service`(`main-service`/`ai-service`), `environment`(`local`/`dev`/`prod`), `traceId`, `message`
- 해당 시: `requestId`, `turnId`, `actionId`, `sessionId`, `endpoint`(또는 `tool`/`agent`), `status`(HTTP 상태 또는 성공/실패), `durationMs`, `errorCode`
- 형식: 한 줄 JSON. Spring은 logback + JSON encoder, FastAPI는 표준 logging + JSON formatter. **local 프로파일만 사람이 읽는 콘솔 패턴 허용**, dev/prod는 JSON 고정.

### 3. 로그 금지 목록·마스킹 규칙

- 금지 목록(본 문서 "로그 금지 정보" 7종 그대로): 비밀번호·해시 / JWT·refresh token·API key / `.env` 실값 / PDF 전체 텍스트 / 학생 답안·대화 원문 / 비공개 정답·루브릭 / 내부 chain-of-thought
- 원칙: **치환(`***`)보다 비로깅 우선** — 금지 필드는 로그 객체에 아예 넣지 않는다.
- 요청/응답 body는 기본 미로깅. 디버깅 필요 시에도 금지 목록 필드는 제외한 요약만 로깅.
- `Authorization` 헤더·`Cookie` 값 로깅 금지. 예외 stack trace는 그대로 남기되, 예외 메시지에 토큰을 포함시키지 않는 것을 코드 규칙으로 한다(마스킹 테스트로 검증).
- 외부 LLM(Gemini) 호출 로그(AI Service): 모델명·소요 시간·성공/실패 분류·재시도 횟수만. 프롬프트·응답 원문은 기본 미로깅(디버그 옵션에서도 금지 목록 준수).

### 4. health / readiness

| 서비스 | 엔드포인트 | 의미 | 응답 |
| --- | --- | --- | --- |
| Main | `GET /api/health` (기구현 유지) | liveness — 프로세스 생존 | 200 고정 |
| Main | `GET /api/health/ready` (신설) | readiness — 의존성 확인 | 아래 참조 |
| AI | `GET /health` | liveness — 프로세스 생존 | 200 고정 (외부 LLM 연결성 체크는 비용 문제로 미포함) |

- `/api/health/ready` 응답: `{"status": "UP"|"DEGRADED"|"DOWN", "checks": {"db": "UP"|"DOWN", "aiService": "UP"|"DOWN"}}`
  - DB DOWN → 전체 `DOWN` + **503** (핵심 의존성)
  - DB UP + AI Service DOWN → `DEGRADED` + **200** (인증·자료 등 비-AI 기능은 동작하므로 트래픽 수용, 상태만 표기)
- 인증 불필요(공개 엔드포인트), 배포 healthcheck·smoke test가 사용.

### 5. 환경별 로그 레벨

- local=DEBUG, dev=INFO, prod=INFO (오류 알림 도구는 MVP 이후 — dev 단계는 서버 로그 파일 + `docker compose logs`로 운영)

## 완료 조건

- [x] 추적 ID와 공통 로그 필드가 승인됐다.
- [ ] 한 요청을 Main과 AI 로그에서 연결할 수 있다.
- [ ] AI 호출 시간과 오류 유형을 확인할 수 있다.
- [ ] 민감정보 마스킹 테스트가 통과한다.
- [ ] local/dev/prod 로그 레벨이 분리된다.
- [ ] health/readiness 기준이 문서화됐다.
- [ ] 핵심 메트릭·알림 후보와 운영 책임자가 정해졌다.
- [ ] 장애 조사 절차가 문서화됐다.
