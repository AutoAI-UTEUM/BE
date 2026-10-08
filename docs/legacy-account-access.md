# 기존 계정 이용 유지 (#478, #471 연계)

사용자가 승인한 기존 계정 이용 유지 정책을 `users.access_cohort`로 구분한다. V58 적용 시점에 존재하는 계정은 `LEGACY_EXEMPT`, 이후 신규 LOCAL/Google 계정 및 DB 기본값은 `NEW_SIGNUP`이다. DOB 누락, 오래된 생성일, 이메일 UNKNOWN 상태를 신규 계정의 예외 근거로 사용하지 않는다. API 요청으로 cohort를 변경할 수 없다.

이 표시는 연령·이메일·보호자 확인 증거가 아니다. 기존 계정의 DOB NULL, age UNKNOWN, email UNKNOWN과 nullable 확인 시각을 유지한다. 보호자 접수 PENDING도 승인 상태로 바꾸지 않는다. 기존 계정에는 DOB 재입력을 요구하지 않고 이메일·연령 gate의 기존 계정 예외로 이용을 유지한다.

`EmailVerificationGate`와 `AgeEligibilityGate`는 현재 DB의 정지·탈퇴 상태를 먼저 거부한 후 예외를 적용한다. 기존 소유권, 역할, 강의실 멤버십, 탈퇴 및 정책 동의 검사는 그대로 적용한다. 예외는 타인의 자료·세션·리포트 접근 권한을 주지 않는다. 신규 계정은 이메일 확인 후에도 연령 UNKNOWN/MANUAL_PENDING이면 보호 업무 API·파일·SSE·기존 AI 전송 경계에서 거부된다. SSE 및 턴 완료 경계는 현재 committed account/role/eligibility projection으로 같은 예외를 적용한다. [업무 차단 경계](business-eligibility-gate.md). 생년 판정 기준과 실제 보호자 승인 절차는 미정이며 승인 상태를 만들지 않았다.

로그인 및 `/api/users/me`, 이메일 상태 응답의 `emailVerificationRequired`는 기존 예외 계정에 `false`를 반환한다. 이때 `emailVerification`은 `UNKNOWN` 또는 `PENDING`일 수 있으므로 UI는 이를 이메일 확인 성공으로 표시하지 않는다. 새로운 응답 필드·승인 endpoint·가입 제한 해제 flag는 추가하지 않는다.

V58은 이전 V53–V57 뒤에 적용한다. 기존 행에 cohort를 추가한 후 DB 기본값을 NEW_SIGNUP으로 전환하고 허용 값 CHECK를 적용한다. JPA 신규 계정 생성도 명시적으로 NEW_SIGNUP을 저장한다. 이미 사용 중인 DOB나 확인 증거는 migration이 변경하지 않는다.

검증은 H2 및 별도 loopback MySQL 합성 DB에서 migration 전후 계정 구분, 신규 계정 차단, 기존 계정의 PDF/SSE 접근, 타인 접근 거부, 정지·탈퇴 거부, 응답 표시 및 확인 증거 불변성을 다룬다. 실제 DEV migration, 보호자 확인 성공, 실메일·실학생·외부 AI 검증은 이 변경의 완료 범위에 포함하지 않는다.
