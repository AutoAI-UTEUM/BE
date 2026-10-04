# 보호자 웹 동의·문자 확인 접수 기반 (#491)

사용자가 선택한 정상 흐름은 **웹 동의 링크 → 보호자 휴대전화 문자 확인**, 예외 흐름은 **실패·불일치·분쟁 시 수동 확인**이다. 이 변경은 비활성 BE 계약과 접수 상태를 구현한다. 실제 문자 업체는 연결하지 않는다. 휴대전화 소유 확인과 법정대리인 자기신고만으로 보호자 관계를 확인했다고 처리하지 않는다.

## 현재 구현 경계

- 기본 `edupilot.guardian.web.enabled=false`. 실제 adapter가 없으면 `connected=false`이며 성공을 반환하는 운영 stub은 없다. 활성 플래그만 바꿔도 문자 확인 성공으로 진행할 수 없다.
- 신규 계정에만 로그인 후 링크를 발급한다. 기존 `LEGACY_EXEMPT` 계정은 DOB 재입력·보호자 동의 없이 이용 예외를 유지한다. 정지·탈퇴 차단이 우선한다.
- 토큰은 32바이트 난수이며 DB에는 SHA-256 해시만 저장한다. 링크 토큰은 URL fragment에 넣고 BE에는 JSON 본문으로 전달한다. 조회와 GET은 동의를 소비하지 않는다.
- 발급 시 동의문 버전·digest를 고정한다. 동의와 법정대리인 자기신고 모두 true여야 문자 요청을 접수한다. 동의문 변경 시 재발급이 필요하다. 자기신고는 관계 증명이 아니다.
- 원문 전화번호와 OTP를 저장하지 않는다. 전화 HMAC fingerprint, 동의 시각·자기신고, 업체 참조, 확인 시각, 시도 횟수, 예외 사유를 저장한다. DTO 문자열에는 비밀값이 나오지 않고 업체 오류 원문을 응답·로그에 전달하지 않는다.
- 업체 호출 전 상태·시도 nonce를 커밋하고 DB 트랜잭션 밖에서 호출한다. 같은 nonce와 현재 계정 상태를 재검사하여 중복, 재발급·탈퇴 후 늦은 결과를 배제한다. 탈퇴 시 취소·전화 fingerprint/참조 제거가 계정 변경과 함께 롤백된다.
- MySQL REPEATABLE_READ에서 잠금 전 소유자 조회가 이전 snapshot을 만들 수 있으므로, User 잠금 이후 요청 행은 PESSIMISTIC_WRITE current read로 읽는다. 준비·완료·재발급 취소·탈퇴 취소·만료 회수 모두 User → 요청 행 잠금 순서를 유지한다. 실제 MySQL에서 snapshot 생성 후 잠금 대기를 강제한 회귀를 별도로 실행한다.
- 중단된 SENDING/VERIFYING은 기한 후 `REVIEW_REQUIRED / PROVIDER_RESULT_UNKNOWN`, PHONE_PENDING 기한 초과는 EXPIRED 예외로 회수한다. 자동 SMS 재발송은 없다.
- 계정별 링크 3회/시간과 코드 시도 한도는 DB로 유지한다. 요청 IP 10회/시간, 공개 확인 작업 IP 10회/15분은 인스턴스 메모리 한도다. 기본 링크 30분·문자 5분·코드 5회는 기술적 만료 설정이며 개인정보 보존기간을 정하지 않는다.

## FE 계약

모든 경로는 `/api/auth/guardian-verification` 아래의 POST이며 성공 응답은 `ApiResponse.data` 구조, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 쓴다.

| 경로 | 인증·본문 | 결과 |
| --- | --- | --- |
| `/link` | 로그인, 본문 없음 | `{url, expiresAt}`, `${portalBaseUrl}/guardian-consent#token=…` |
| `/view` | 공개, `{token}` | 상태·고정 동의문 버전/digest·현재 동의문 URL, 개인정보 없음 |
| `/consent` | 공개, `{token, noticeVersion, accepted:true, declaresLegalGuardian:true, phone}` | E.164 전화로 요청, PHONE_PENDING 또는 예외 |
| `/verify` | 공개, `{token, code}` | PHONE_CONFIRMED 또는 불일치/업체 예외, OTP 4~8자리 숫자 |
| `/dispute` | 공개, `{token}` | REVIEW_REQUIRED / DISPUTED |

상태 응답은 `state`, `noticeVersion`, `noticeDigest`, `noticeUrl`, `expiresAt`, `consentRecorded`, `phoneControlConfirmed`, `guardianRelationshipVerified`, `exceptionReason`이다. 동의문이 변경되면 기존 링크의 noticeUrl은 null이며 consent는 409다. **PHONE_CONFIRMED여도 guardianRelationshipVerified=false이며 User 연령 상태는 그대로**다. FE는 가입 완료·이용 자격 승인으로 표현하면 안 된다. FE 화면·실문자 수신 구현은 이 BE 변경에 포함되지 않는다.

## 업체 연결 전에 필요한 결정

실제 SMS 업체와 비용·개인정보 처리 계약, 보호자 관계 증거·정상 승인 기준, 예외 확인 운영자 권한, 출생연도/생일 경계, 동의 철회 API와 증거 보존·삭제 기준을 확정해야 한다. 이 단위는 운영자 승인 API, 연령 계산, 신규 계정의 전체 API/외부 AI 연령 gate 연결을 제공하지 않는다. UNKNOWN/PENDING을 승인 상태로 변경하지 않으며 기존 이메일 gate와 필수 약관 동의를 유지한다.

`GuardianPhoneProvider` adapter는 실제 연결 준비 상태, 공급자 idempotency, 제한 시간 안의 send/verify, `UNAVAILABLE/REJECTED/RESULT_UNKNOWN` 오류 분류를 구현해야 한다. expiresAt 뒤의 결과는 BE가 적용하지 않는다. 공급자가 결과를 보장하지 못하면 UNKNOWN이어야 한다. 실제 통신·요금·신원/관계 확인은 이 단위의 검증 완료 범위가 아니다.

설정은 `edupilot.guardian.web` 아래 `enabled`, `portal-base-url`, `notice-version`, `notice-digest`, `notice-url`, `link-ttl`, `phone-ttl`, `max-code-attempts`다. HTTPS URL, 동의문 SHA-256 digest, 유효 만료값, 업체 준비 상태가 모두 필요하다. 실제 환경설정·키를 만들거나 활성화하지 않는다.

## 검증 경계

합성 H2/MySQL과 mock 업체로 상태 전이, 중복, 동의문 변경, 업체 결과 불명, 탈퇴/재발급 경합, 중단 회수, API 공개/인증 경계를 검증한다. MySQL 선택형 실행은 `GUARDIAN_WEB_MYSQL=true`, loopback33316의 전용 `guardian_web_synthetic` / `guardian_web_full_migration_synthetic`만 쓴다. migration 테스트는 인스턴스·DB 이름을 검사한 뒤 그 합성 DB만 재생성한다. 실행 결과는 PR에 기록한다. 실사용자·유료 문자·실제 신원/관계 확인·다중 서버/JVM 강제 종료 검증은 수행하지 않는다.
