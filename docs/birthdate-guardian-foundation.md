# 신규 생년월일과 수동 확인 대기 기반 (#478)

상태: 기능 기반 구현·검증 중. 첫 출시의 만14세미만 지원 전체 런타임은 미완료다. 이 단위는 신규 입력 저장과 내부 접수 원장만 제공하며, 연령 판정·보호자 확인 성공을 구현하지 않는다.

## 구현한 범위

- LOCAL `POST /api/auth/signup`의 `dateOfBirth`는 ISO 날짜형 입력으로 필수다. 누락은 400 `VALIDATION_FAILED`다. 새 User를 저장하기 전에 입력을 기록한다.
- 신규 Google 계정 생성에도 `dateOfBirth`가 필요하다. 기존 Google subject 로그인은 필드 누락을 허용하며 기존 DOB를 변경하지 않는다. 비충돌 신규 가입 정보 부족은 기존 409 `SIGNUP_REQUIRED` 경로를 사용한다. Google 로그인·이메일·DOB 입력은 보호자 확인 근거가 아니다.
- `users.date_of_birth`는 사용자 입력값이고 `age_verification_state`는 `UNKNOWN` 또는 `MANUAL_PENDING`이다. DOB 입력만으로 만14세이상, 성인, 보호자 동의 완료 상태를 만들지 않는다. 기존 행에 DOB를 생성하지 않는다. 이 필드는 User/Signup 응답이나 AI DTO에 추가하지 않았다.
- `GuardianIntakeService`는 내부 서비스다. User 행 잠금 아래 `GuardianIntakeProvider`를 호출하며 현재 `ManualGuardianIntakeProvider`는 실제 DB에 PENDING 접수 metadata를 기록한다. 동일 사용자 재접수는 같은 request를 반환한다. 승인·거절·철회·만료·증빙 업로드 API나 승인 담당자 권한을 만들지 않았다. Guardian의 연락처·증빙도 수집하지 않는다.
- provider interface는 접수 단계의 교체 경계이며 네트워크 확인 호출을 현재 DB 트랜잭션에 넣는 인터페이스가 아니다. 향후 업체의 실제 확인은 트랜잭션 밖에서 수행하고 승인된 증거/권한 검증 뒤 별도 저장 경계가 필요하다. 접수된 상태를 실제 확인 완료로 소비하지 않는다.
- `AgeEligibilityGate`는 현재 committed User 상태를 다시 읽고 신규 UNKNOWN에는 `AGE_VERIFICATION_REQUIRED`, PENDING에는 `GUARDIAN_VERIFICATION_PENDING`을 403으로 거절한다. V58 기존 예외는 정지·탈퇴 검사 후 이용을 유지하며 승인 증거가 되지 않는다. 현재 상태 enum에는 승인 상태나 성공 전이가 없다. 신규 DOB나 이메일 확인만으로 통과하지 않는다.
- 탈퇴는 DOB를 제거하고 age 상태를 UNKNOWN으로 초기화하며 PENDING 접수를 CANCELLED로 바꾼다. 신청↔탈퇴는 같은 User 잠금으로 직렬화한다. provider/DB 실패와 rollback은 거짓 대기/승인 상태를 만들지 않는다.

## 아직 연결하지 않은 범위

AgeEligibilityGate를 전체 HTTP 보호 API·파일·SSE·백그라운드 AI 전송 경계에 연결하지 않았다. 이 PR만으로 그 경로의 연령 차단이 완성됐다고 판단하지 않는다. 현재 이메일/역할/동의 게이트와 필수동의 설정을 끄거나 완화하지 않았다. 가입 DOB 누락을 허용하는 feature flag도 추가하지 않았다.

기존 DOB 없는 UNKNOWN 계정은 승인된 V58 `LEGACY_EXEMPT` 정책으로 재입력 없이 이용을 유지한다. 이를 성인이나 이메일/보호자 확인 성공으로 간주하지 않는다. 신규 계정은 `NEW_SIGNUP`이며 DOB 누락을 허용하거나 기존 예외로 변경하지 않는다. 전체 age 경계 연결과 신규 승인 상태는 아직 완료되지 않았으므로 이 기반만으로 출시를 승인하지 않는다. [기존 계정 정책](legacy-account-access.md), [FE 입력·오류 계약](qa/fe-auth-contract/README.md)을 함께 읽는다.

보호자 정상 경로는 이후 웹 동의 → SMS 확인으로 정해졌고 실패·불일치·분쟁만 수동 접수한다. 이 문서의 초기 내부 수동 PENDING 원장은 보호자 확인 성공이나 정상 경로의 최종 구현이 아니다. [웹/SMS 기반](guardian-web-sms-intake.md)은 비활성·미연결 provider 상태이며 PHONE_CONFIRMED도 법적 관계나 연령 이용 승인 상태가 아니다.

추가 결정:

1. 만14세 판정의 기준 날짜·시간대, 윤년 생일 처리, 입력의 과거/미래·정정 정책. 현재는 날짜형 입력을 저장할 뿐 판정 시간대를 선택하거나 연령을 계산하지 않는다.
2. 수동 확인 담당자 권한·필수 증빙·감사, 승인/거절/철회/만료 기준, 증빙과 접수 metadata 보존기간.
3. 신규 계정의 실제 연령/보호자 승인 상태와 전체 보호 API·AI 접근 경계. 기존 예외 이용 정책은 V58로 확정됐다.
4. 실제 증빙 검증·승인 상태와 모든 HTTP/SSE/worker 전송·늦은 결과 경계 연결. 목적별 AI 동의/철회 epoch는 별도 후속 작업이다.

## DB와 검증 경계

V57은 DOB와 UNKNOWN 기본값, 승인 값이 없는 상태 CHECK, user당 하나의 guardian intake 행을 추가한다. V53 → V54 → V55 → V56 → V57 순서를 사용한다. 기존 행은 DOB NULL/UNKNOWN으로 보존하고 VERIFIED로 backfill하지 않는다. 접수 원문/승인 증빙을 보관하는 테이블이 아니다. 아직 metadata 자동 보존/삭제 규칙은 없다.

합성 데이터와 mock Google·AI로 입력/기존 로그인/필수동의 유지/실제 PENDING 저장·중복·rollback/provider 실패·탈퇴 경합을 검증한다. gate 서비스의 거부 검증과 전역 보호 API 통합 검증을 구분한다. 실제 Google·보호자 확인·메일 수신·유료 AI·학생 데이터·운영 migration·배포는 실행하지 않는다. FE DOB 입력 화면 구현/배포도 이 BE 작업의 완료물에 포함하지 않는다.
