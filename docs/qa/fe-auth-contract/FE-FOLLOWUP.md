# FE 후속 계약 확인 답변 (2026-10-04)

PR502 후속 질의의 부모 전달용 답변이다. 외부로 전송하지 않았다. 읽은 후보 snapshot은 `dbd148352753e2b52f8f9cbf8599c5024c61dd8d`, 최신 공개 FE develop은 `1b6987d8472a064a080a00bd43232993e9bd41eb`다. **후보 SHA·capability 계약·FE 활성화 합의는 아직 완료되지 않았다.** 후보는 통합 담당이 갱신 중이며 아래 SHA는 읽은 근거일 뿐 최종 배포 승인 대상이 아니다.

PR502는 이미 후보에 병합됐다. 이 후속 단위는 `feature/471-fe-auth-contract-followup`에서 전용 경로만 보완한다. 앞선 후보와 main-service tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`가 같다. 최신 FE 원격에는 계획한 auth 후속 구현의 공개 head를 확인하지 못했다. 실제 FE 소스의 DOB/consents 누락·이메일 상태 유실 및 route/API 부재는 이전 소비자 검사의 baseline 그대로이며, 구현 예정 메시지를 구현 완료 증거로 사용하지 않는다.

## 부모가 전달할 짧은 답변

1. **필수 정책 0개:** `GET /api/policies/current`에서 현재 `requiresConsent=true` 대상이 0개면 `consents`는 생략 또는 `[]`로 보낸다. BE는 설정 true여도 이 경우를 허용하고 동의 이력을 만들지 않는다. 역직렬화 가능한 배열을 보내도 서비스에서 무시한다. 잘못된 JSON·알 수 없는 policy enum 등 요청 파싱 오류는 이 예외로 허용되지 않는다.
2. **기존 UNKNOWN:** V58에서 기존 계정은 LEGACY_EXEMPT로 이용을 유지한다. UNKNOWN/PENDING + `emailVerificationRequired=false`는 인증 성공 표시가 아니다. 신규 UNKNOWN/PENDING은 차단을 유지한다. 최신 후보에서 초기 “기존 UNKNOWN도 차단” 설명과 DOB 없는 LOCAL/신규 Google 예시는 공통 문서 담당이 정정한 것을 확인했다. 기존 Google subject 로그인은 DOB 재입력이 필요 없다.
3. **실메일 담당:** 개인 담당은 현재 문서·#471 assignee에서 확정되지 않았다. 부모가 BE/인프라 운영 담당을 지정하고 실수신 시험의 별도 승인 범위를 마련해야 한다. FE 담당은 준비된 route·사용자 POST·상태 재조회 동선을 검수한다. 202·logging provider 수락은 실수신 증거가 아니다.
4. **링크 origin:** `EDUPILOT_MAIL_BASE_URL`로 만든 `<유효 base>/verify-email?token=<token>`이다. application/base Compose 기본은 `https://dev.uteum.com`, prod override 기본은 `https://www.uteum.com`이다. DEV workflow도 prod override를 함께 쓰므로 실제 DEV 값은 `.env` 및 유효 설정 확인 전 단정할 수 없다. API base URL과 별개인 FE 확인 route origin을 맞춰야 한다.
5. **capability:** 현재 BE에는 가입/DOB/이메일 확인의 명시적 capability·revision 응답이 없다. FE의 `VITE_API_CAPABILITIES`는 FE 빌드 설정이며 BE 신호가 아니다. 새 API 없이도 승인된 BE/FE 배포 manifest·통제된 전환 창·인증된 합성 status/me 및 실제 메일/UI 인수로 수동 활성화하는 최소안을 검토할 수 있다. [preflight의 수동 활성화안](../../launch-deployment-preflight.md)을 먼저 합의하고, 자동 서버별 계약 선택이 반드시 필요할 때만 아래 미구현 공개 read-only 제안을 검토한다. 최종 후보 SHA/FE build·메일 준비 합의 전에는 활성화 완료로 표시하지 않는다. FE 기본 OFF는 새 BE의 DOB 필수/이메일 gate를 완화하지 않는다.

## 정책·오류·예시의 정확한 근거

[PolicyService.validateSignup](../../../main-service/src/main/java/io/edupilot/policy/PolicyService.java)은 `required.isEmpty()`이면 설정/배열 검증에 앞서 `SignupSelection(null,null,null)`을 반환한다. `recordSignup`은 selection의 agreedAt이 null이면 이력을 쓰지 않는다. [PolicyServiceTest](../../../main-service/src/test/java/io/edupilot/policy/PolicyServiceTest.java)의 `mandatorySettingDoesNotBlockSignupWhenNoDocumentRequiresConsent`와 `optionalSignupIgnoresDocumentsThatDoNotRequireConsent`가 이 계약을 명시한다. 해당 Java 테스트는 이번 단위에서 실행하지 않았다.

대상이 있으면 설정 true에서 배열이 필요하고, false에서는 생략/빈 배열을 허용한다. 배열을 보내면 현재 동의 대상 버전을 정확히 한 번씩 보내야 한다. 단, 대상이 아닌 **well-formed** 항목은 무시한다. 대상이 있는 상태의 null 항목/type/version은 `POLICY_CONSENT_REQUIRED`이며 알 수 없는 enum 등은 DTO 파싱 단계의 `MALFORMED_REQUEST`다. 0개일 때도 `[]`를 선호하며, 0개를 근거로 정책 게시 준비나 이미 켜진 필수 설정을 변경하지 않는다.

현재 [ErrorCode](../../../main-service/src/main/java/io/edupilot/global/error/ErrorCode.java)의 POLICY_CONSENT_REQUIRED 원문은 “현재 이용약관과 개인정보처리방침에 모두 동의해 주세요.”다. 실제 요구 대상은 `requiresConsent=true` 항목이며 두 종류 모두가 항상 대상인 것은 아니다. FE는 code와 현재 정책 목록으로 “현재 필요한 정책 동의를 확인해 주세요.”처럼 안내한다. 실제 원문을 fixture에서 거짓으로 바꾸지 않는다. 런타임 담당에게 “현재 필수 정책에 동의해 주세요.” 같은 범위에 맞는 오류 문구 검토를 제안한다. 오류 코드·설정·런타임 문자열은 이 작업에서 수정하지 않는다.

[V58](../../../main-service/src/main/resources/db/migration/V58__legacy_account_access_cohort.sql)과 [User.isEmailVerificationRequired](../../../main-service/src/main/java/io/edupilot/user/User.java)의 식은 `!isEmailVerified() && !isLegacyAccessExempt()`다. false만으로 cohort를 추정하지 않고, 기존 이용 예외도 정지/탈퇴·권한/소유권·정책 조건을 대신하지 않는다. [기존 계정 정책](../../legacy-account-access.md), [이메일 문서](../../email-verification.md), [API 명세](../../api-spec.md)의 현재 후보 내용이 이를 일치시킨다.

공통 문서 정정은 후보에 포함된 `2156757`의 작업이다. LOCAL·신규 Google 예시에 `dateOfBirth: "2000-01-01"`을 넣고, 현재 동의 대상 0개 및 V58 이후 예외를 정정했다. 공통 문서를 이 후속 단위에서 다시 수정하지 않는다. DOB 미래 날짜 제한·정정·연령 기준·보호자 승인도 새로 결정하지 않는다.

## 메일 준비와 link base URL

[EmailVerificationService](../../../main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java)는 `/verify-email?token=` 상대 링크를 [EmailTemplates](../../../main-service/src/main/java/io/edupilot/mail/EmailTemplates.java)에 넘긴다. 템플릿은 base의 끝 slash와 상대 링크의 앞 slash를 제거해 연결한다. base에 `/api`를 넣으면 `/api/verify-email`이 되어 FE route와 어긋날 수 있다. 현재 코드의 단순 문자열 조합이 올바른 origin을 자동 보장하지는 않는다.

설정 근거는 [application.yml](../../../main-service/src/main/resources/application.yml), [base Compose](../../../docker-compose.yml), [prod override](../../../docker-compose.prod.yml), [DEV workflow](../../../.github/workflows/deploy-dev.yml)다. DEV는 두 Compose 파일과 `.env`를 사용한다. 현재 실제 값·배포 FE build·메일 수신은 조회/실행하지 않았다.

실메일 운영 담당이 별도 승인 아래 확인할 사항은 활성 provider/from·검증 sender/domain/region·사용 권한, 승인된 FE origin의 명시적 확인 UI, 유지되는 outbox 암호화 키와 worker 상태, 합성 수신 주소·비용/횟수 범위, 재발급·30분 만료·1회 사용 결과다. 구체적 절차와 rollout/rollback 경계는 [배포 사전 점검](../../launch-deployment-preflight.md)을 재사용한다. 실수신 주소·키·본문·실사용자를 이 문서에 넣지 않는다.

## BE capability 제안 — 미구현·미합의

현재 [AuthController](../../../main-service/src/main/java/io/edupilot/auth/AuthController.java), [이메일 controller](../../../main-service/src/main/java/io/edupilot/auth/EmailVerificationController.java), [login DTO](../../../main-service/src/main/java/io/edupilot/auth/dto/LoginResponse.java)에 auth capability/revision 신호가 없다. [HealthController](../../../main-service/src/main/java/io/edupilot/global/config/HealthController.java)의 health는 `data:{status:"UP"}`이고 readiness는 DB·AI 확인이다. auth 지원·메일 수신·배포 SHA를 나타내지 않는다. BE의 다른 `capabilities` 검색 결과는 AI 턴/퀴즈의 요청 기능에 관한 것이다. FE [capabilities.ts](https://github.com/AutoAI-UTEUM/FE/blob/1b6987d8472a064a080a00bd43232993e9bd41eb/src/shared/config/capabilities.ts)는 `import.meta.env.VITE_API_CAPABILITIES`만 읽는다.

FE가 수동 배포 계약 고정으로 충분하다고 합의하면 이 API를 추가할 필요가 없다. 로그인 전에 서버별 LOCAL 입력 계약을 **자동으로 선택해야 하는 요구가 확인되는 경우의 제안**은 공개 read-only `GET /api/auth/capabilities`에서 공통 envelope와 `Cache-Control:no-store`로 다음 최소 정보를 제공하는 것이다. 아래 이름/경로/버전도 합의 전 초안이며 지금 호출할 endpoint가 아니다.

```json
{
  "success": true,
  "data": {
    "contractVersion": "signup-dob-email-v1",
    "backendRevision": "<빌드에 주입한 정확한 40자 commit SHA>",
    "signupDateOfBirthRequired": true,
    "signupPolicyConsentsSupported": true,
    "emailVerificationSupported": true,
    "legacyAccessPolicy": "cohort-v58"
  },
  "message": "요청이 성공했습니다."
}
```

자동 선택 요구를 수용할 경우 런타임 담당과 검토할 작업:

- 경로·DTO/버전·구형 서버 404/필드 부재·오류 응답과 FE 기본 OFF 조건을 FE 담당과 합의한다. 신호는 가입 API 지원을 설명하며 이메일/연령 확인 증거나 전송 허가가 아니다.
- backendRevision을 실제 실행 artifact의 build metadata에서 공급한다. UI 입력·요청 body·수동 추정 SHA로 성공을 꾸미지 않는다. release manifest의 exact SHA 및 runtime image digest와 대조할 책임을 둔다.
- 현재 mail enabled/provider가 지원 flag와 같다고 추정하지 않는다. 실수신 준비는 운영 담당의 별도 증거로 관리하고, public 응답에 키·수신자·메일 payload·보호자 승인 정보를 노출하지 않는다.
- BE/FE exact head, 허용 origin·CORS, V58 적용 및 기존/신규 구분, 기본 OFF→ON 전 합성 입력·확인/403·기존 예외 검증을 합의한다. FE flag OFF/404/신호 불일치가 신규 계정을 LEGACY_EXEMPT/VERIFIED로 만들거나 서버 gate를 끄는 경로가 되어서는 안 된다.

login/me의 이메일 필드 존재, 공개 OpenAPI, 단순 endpoint probe는 부분적인 소스/응답 정보일 뿐 위 합의 신호나 실제 메일 준비를 대체하지 않는다. 구형 응답의 선택 필드는 보존 가능하지만 없는 값을 false/VERIFIED/legacy로 만들어서는 안 된다. **FE 기본 OFF 상태로 새 BE만 rollout하면 현재 가입 400/409와 신규 업무 403 문제는 계속 남는다.** 별도 rollout 합의와 준비 완료가 필요하다.

## 검증 경계

기존 소비자 baseline 25개와 이 후속의 policy·메일·capability·공통 문서 source 대조 4개를 경량 실행한다. source 검사는 Java 실행이나 배포된 서버 확인이 아니다. 실제 FE 후속 UI/flag/code 변경·브라우저 E2E·실Google/메일/SMS·실계정·DEV DB·설정 변경·배포는 미실행이다. 최종 숫자와 head는 [EVIDENCE.md](EVIDENCE.md)에 기록한다.
