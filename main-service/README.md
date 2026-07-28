# EduPilot Main Service

EduPilot의 인증·권한·영속 상태와 Frontend용 외부 API를 담당하는 Spring Backend입니다. 공통 기반, 인증·사용자 API, Material 처리 수명 주기, 학습 세션 경계와 Quiz 저장·조회·제출·채점 수명 주기가 구현되어 있습니다.

## 요구 환경

- Java 21
- Spring Boot 4.1.0
- MySQL
- Windows에서는 저장소에 포함된 `gradlew.bat` 사용

## 환경 변수

`.env.example`에는 로컬 개발용 가짜 값만 있습니다. `.env`는 자동으로 읽지 않으므로 실제 값은 셸이나 IDE 실행 설정으로 주입합니다.

| 변수 | local 예시 | 설명 |
| --- | --- | --- |
| `EDUPILOT_DB_URL` | `jdbc:mysql://localhost:3306/edupilot` | MySQL JDBC URL |
| `EDUPILOT_DB_USERNAME` | `edupilot` | DB 사용자 |
| `EDUPILOT_DB_PASSWORD` | `local-dev-password` | DB 비밀번호 |
| `EDUPILOT_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 콤마 구분 허용 origin, `*` 금지 |
| `EDUPILOT_JWT_SECRET` | Base64 인코딩된 32바이트 가짜 값 | JWT HS256 secret, 디코딩 후 최소 256bit |
| `EDUPILOT_AI_BASE_URL` | `http://localhost:8000` | FastAPI AI Service base URL |
| `EDUPILOT_INTERNAL_TOKEN` | `replace-with-local-internal-token` | Spring–FastAPI 내부 인증 토큰 |
| `EDUPILOT_STORAGE_DIR` | `./storage` | 원본 PDF를 저장할 로컬 볼륨 루트. prod에서는 필수 |
| `EDUPILOT_UPLOAD_MAX_MB` | `45` | PDF 업로드 최대 크기(MB) |
| `EDUPILOT_AI_EXTRACT_READ_TIMEOUT` | `120s` | PDF 추출 내부 API read timeout |
| `EDUPILOT_AI_TURN_READ_TIMEOUT` | `200s` | 동기 turn 내부 API read timeout(AI turn 총 예산 180s보다 20s 여유) |
| `EDUPILOT_AI_STREAM_IDLE_TIMEOUT` | `30s` | NDJSON 최초 이벤트·이벤트 사이 최대 무응답 시간(heartbeat 포함) |
| `EDUPILOT_AI_GRADE_READ_TIMEOUT` | `90s` | SHORT/ESSAY 채점 내부 API read timeout |
| `EDUPILOT_AI_PIPELINE_READ_TIMEOUT` | `45s` | assessment·diagnosis 내부 API read timeout(v0.4 §4) |
| `EDUPILOT_QUIZ_PASS_RATIO` | `0.6` | 퀴즈 통과 비율(0~1) |

`prod` 프로필에는 DB·인증·AI·저장소 변수를 모두 명시해야 합니다. `local`은 DB URL·사용자·CORS origin·AI base URL·저장소 경로·업로드 제한에 개발 기본값이 있지만 DB 비밀번호, JWT secret, 내부 인증 토큰은 반드시 환경 변수로 주입합니다. 두 서비스에는 같은 `EDUPILOT_INTERNAL_TOKEN` 값을 사용합니다.

## 실행과 검증

```powershell
$env:EDUPILOT_DB_PASSWORD='local-dev-password'
$env:EDUPILOT_JWT_SECRET='MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
$env:EDUPILOT_INTERNAL_TOKEN='replace-with-local-internal-token'
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

```text
Health:     http://localhost:8080/api/health
OpenAPI:    http://localhost:8080/v3/api-docs
Swagger UI: http://localhost:8080/swagger-ui.html
```

테스트 프로필은 DB/JPA/Flyway 자동 설정을 제외하여 공통 Web 계약 테스트가 로컬 MySQL에 의존하지 않게 합니다. Flyway 호환성은 빈 MySQL 스키마에서 local 프로필을 기동해 별도로 확인합니다.

## 인증 흐름

회원가입 후 로그인하면 access token은 응답 body로, refresh token은 `edupilot_refresh` HttpOnly 쿠키로 발급됩니다. access 만료 시 `/api/auth/refresh`가 쿠키를 회전하며 로그아웃과 탈퇴는 저장된 refresh를 폐기합니다.

## FastAPI 연동 로컬 검증

기본 테스트는 MockWebServer를 사용하므로 FastAPI나 외부 네트워크가 필요하지 않습니다.

```powershell
.\gradlew.bat test --no-daemon
```

실제 서비스 계약 검증은 `ai-service` 구현 완료 후 수행합니다. 루트 README의 순서와 같이 `MySQL → FastAPI(:8000) → Main Service(:8080)` 순서로 실행하고, 두 서비스에 같은 내부 토큰을 주입합니다.

```powershell
$env:EDUPILOT_AI_BASE_URL='http://localhost:8000'
$env:EDUPILOT_INTERNAL_TOKEN='replace-with-local-internal-token'
.\gradlew.bat test -Dit.ai=true --tests io.edupilot.ai.AiClientLiveTest --no-daemon
```

live 테스트는 기본 테스트와 CI에서 비활성화됩니다. 활성화하면 FastAPI의 `/health`와 `/internal/ai/turn`을 각각 호출해 `schemaVersion`·`turnId`를 확인하고, 잘못된 내부 토큰이 거부되는지도 검증합니다. health 경로는 현재 `/health`가 기본값이며 필요하면 `EDUPILOT_AI_HEALTH_PATH`로 변경할 수 있습니다.

## Material 처리 흐름

`POST /api/materials`는 PDF 원본과 `PROCESSING` 메타데이터를 저장한 뒤 즉시 응답합니다. 트랜잭션 커밋 후 전용 executor가 `/internal/ai/extract`를 호출하고, 정상 결과는 페이지 문맥과 함께 `READY`, 오류·300페이지 초과는 `FAILED`로 전이합니다. 삭제된 자료에 늦게 도착한 추출 결과는 폐기합니다.

원본 PDF는 소유자 인증 후 `GET /api/materials/{materialId}/file`로 스트리밍합니다. 추출 텍스트 API는 local/dev에서만 활성화되며 운영 프로필에는 등록되지 않습니다.

## 학습 세션 흐름

READY 자료로 `POST /api/sessions`를 호출하면 ACTIVE 세션을 생성하거나 기존 세션을 재사용합니다. 페이지 이동은 LLM 없이 `StateReducer`가 처리하고, 상세·messages API로 현재 페이지와 대화를 복원합니다. 동일 turn `requestId`는 409로 거부하며 세션당 동시 turn은 하나만 허용합니다.

turns API는 사용자 메시지를 짧은 선행 트랜잭션에 저장한 뒤 `/internal/ai/turn`을 호출합니다. AI 응답은 허용된 message type·UI action·statePatch만 반영하며, 최종 저장 직전에 세션을 다시 잠가 ACTIVE 상태와 claim을 재검증합니다. AI 호출 중 세션이 완료·삭제되면 결과를 저장하지 않습니다.

원격 오류가 `retryable=true`인 `TIMEOUT` 또는 `INTERNAL`일 때만 최대 한 번 재시도합니다. v0.4에 따라 각 시도는 새 `turnId`를 사용하고 외부 `requestId`와 traceId는 유지합니다. 퀴즈 생성은 Spring이 저장한 ID만 `state.activeQuizId`에 반영하며, 진단 답변은 `ANSWERED → COMPLETED`, QA는 `START_NEW`/`FOLLOW_UP` thread로 영속화합니다.

## Quiz 처리 흐름

퀴즈 생성 턴은 `QuizService#createFromGeneration`으로 QuizAgent 생성 JSON의 문항 수·범위·유형별 필드와 ESSAY rubric weight를 검증하고 공개 문제와 비공개 정답을 분리 저장합니다. 공개 조회는 최신 세션 퀴즈 100건과 개별 공개 문항만 반환합니다.

제출은 세션 소유권·ACTIVE 상태·1회 제한·답안 구조를 확인합니다. MCQ/OX는 Spring이 저장 정답으로 채점하고 SHORT/ESSAY는 `/internal/ai/grade` 결과를 재검증합니다. 제출·채점·기본 UI 액션을 커밋한 뒤 같은 HTTP 요청에서 assessment를 호출하고, 기준 미달일 때만 diagnosis를 호출합니다. AI 호출 중에는 DB 트랜잭션을 유지하지 않으며 결과 저장 시 세션을 다시 잠금 조회합니다. 그사이 세션이 `COMPLETED` 또는 `DELETED`가 되면 늦게 도착한 결과는 폐기합니다.

제출 후 assessment·diagnosis 실패는 저장된 제출·채점에 영향을 주지 않고 HTTP 200과 기본 이동 액션으로 격리합니다. diagnosis 실패 시 assessment는 유지됩니다. 제출이 1회 제한이므로 누락된 assessment를 외부에서 다시 생성하는 API는 현재 없으며, 관리자 재실행·복구 배치·실패 작업 큐/outbox는 후속 개선 항목입니다.

학습자 메모리는 `GET /api/users/me/memory?materialId={materialId}`로 자료별 공개 요약만 제공합니다. 독립 근거 2회 이상·confidence 0.70 이상 후보의 승격 서비스는 준비되어 있으며, turn 응답의 `memoryWrite` 연결은 Epic 5 범위입니다.

## 패키지 구조

```text
io.edupilot
├─ auth             # 회원가입·로그인·JWT·refresh 회전
├─ user             # 내 정보·탈퇴와 사용자 영속 모델
├─ material         # PDF 저장·업로드·조회·비동기 추출·논리 삭제
├─ session          # 세션 수명 주기·페이지 이동·turn claim·메시지 복원
├─ quiz             # 퀴즈 공개/비공개 저장·조회·제출·채점
├─ assessment       # 제출 후 평가 저장·조회와 메모리 후보 생성
├─ diagnosis        # 진단 질문·답변·교정 결과 상태 전이
├─ memory           # 자료별 학습자 메모리 조회·후보 승격
├─ ai
│  ├─ dto         # Spring–FastAPI 내부 요청·응답 계약
│  └─ AiClient    # 내부 인증, timeout, 오류 매핑을 캡슐화한 HTTP 어댑터
└─ global
   ├─ config      # CORS, OpenAPI, health
   ├─ error       # 에러 코드, 비즈니스 예외, 전역 예외 처리
   ├─ response    # 성공·실패 envelope
   └─ security    # traceId 필터
```

향후 도메인 패키지는 `controller → service → repository` 방향으로만 의존하며, 상위 계층을 하위 계층에서 역참조하지 않습니다. 빈 계층이나 미사용 도메인 패키지는 미리 만들지 않습니다.
