# EduPilot Main Service

EduPilot의 인증·권한·영속 상태와 Frontend용 외부 API를 담당하는 Spring Backend입니다. 현재는 Epic 1 공통 기반만 구현되어 있으며 학습 도메인 endpoint는 아직 없습니다.

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

`prod` 프로필에는 네 변수를 모두 명시해야 합니다. `local`은 URL·사용자·CORS origin에 개발 기본값이 있지만 비밀번호는 반드시 환경 변수로 주입합니다.

## 실행과 검증

```powershell
$env:EDUPILOT_DB_PASSWORD='local-dev-password'
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

## 패키지 구조

```text
io.edupilot
└─ global
   ├─ config      # CORS, OpenAPI, health
   ├─ error       # 에러 코드, 비즈니스 예외, 전역 예외 처리
   ├─ response    # 성공·실패 envelope
   └─ security    # traceId 필터
```

향후 도메인 패키지는 `controller → service → repository` 방향으로만 의존하며, 상위 계층을 하위 계층에서 역참조하지 않습니다. 빈 계층이나 미사용 도메인 패키지는 미리 만들지 않습니다.
