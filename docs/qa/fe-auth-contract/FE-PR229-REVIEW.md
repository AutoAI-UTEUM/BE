# FE PR229 이후 계약 소비자 재확인 (2026-10-04)

[PR229](https://github.com/AutoAI-UTEUM/FE/pull/229)의 develop squash merge `5e91b5789daa13b3a4923653285709d18f232c79`를 별도 읽기 전용 worktree로 확인했다. 원 PR head는 `43467a8343053e41f48d9667469dc110ceb32018`다. 이 문서는 이전 [계약](README.md)·[후속 질의](FE-FOLLOWUP.md)의 source snapshot 이후 결과이며 endpoint/운영 점검 문서를 다시 만들지 않는다.

검토한 BE 및 독립 브랜치 base는 `4e578c5f811d0084de0b80ee72b5ed7c72ba84c6`다. `main-service` tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`는 앞선 `ee69e425`·PR502·PR506 단위와 같다. GitHub compare에서도 PR506 head→현재 base에 main-service 변경이 없는 것을 확인했다. **이 SHA는 읽은 후보이며 최종 배포·FE 활성화 합의 대상이 확정됐다는 뜻이 아니다.**

## 기존 KNOWN GAP5의 변화

`consumer.test.mjs`는 원 FE `1b6987d8472a064a080a00bd43232993e9bd41eb`의 누락 재현 기록으로 유지한다. 최신 상태는 `consumer-pr229.test.mjs`가 별도로 검증한다. 아래 ON은 harness에 합성 환경값을 주입한 조건이며 실제 FE 설정을 켠 것이 아니다.

| 원 누락 | 최신 FE 소스/실행 결과 | 남는 조건 |
| --- | --- | --- |
| 1. LOCAL DOB/consents 누락, DOB 오류 매핑 누락 | ON에서 실제 가입 builder가 두 필드를 전송하고 `dateOfBirth` details를 `AuthValidationError.formErrors`로 매핑한다. `SignupResponse`의 userId/PENDING/required를 반환하고 없는 verifiedAt/login grant는 만들지 않는다. | OFF에서는 DOB/consents가 여전히 body에서 빠진다. |
| 2. 신규 Google DOB/consents 누락 | ON + role 입력에서 두 필드를 전송한다. token-only 초기/기존 로그인에는 강제하지 않고 SIGNUP_REQUIRED/EMAIL_ALREADY_EXISTS를 보존한다. | OFF에서는 신규 추가 입력을 보내지 않는다. |
| 3. login 이메일 3필드 유실 | readiness와 관계없이 optional 필드를 보존한다. | 구형 필드 부재도 부재로 남긴다. false/VERIFIED/legacy를 만들어 채우지 않는다. |
| 4. me/Google 신규·기존 이메일 상태 유실 | PENDING/required=true와 UNKNOWN/required=false, nullable verifiedAt을 보존한다. | required=false는 인증 성공·cohort 증거가 아니다. |
| 5. 이메일 API·type/UI·공개 route 연결 없음 | 이메일 API는 별도 `emailVerificationRepository`의 status/request/confirm 함수로 구현됐다. ON에서 Bearer 본인 status/request, 공개 수동 confirm POST·cookie/Bearer 생략을 실제 모듈로 재현했다. DOB/type/route/UI와 현재 계정 status/me 재조회 연결도 source로 확인했다. | OFF에서는 세 함수 모두 AUTH_CONTRACT_UNAVAILABLE로 HTTP 호출 전에 거절한다. UI/경쟁 상태는 이번 Node harness에서 실행하지 않았다. |

따라서 **기존 5개 누락은 준비된 ON 코드에서 해소**됐고, 이 결과로 default OFF 빌드와 새 BE의 실제 연동이 완성됐다고 주장할 수 없다. 특히 login/me 필드 보존과 403 hold는 readiness OFF에서도 동작하므로 미확인 신규 사용자가 대기 안내로 가더라도 확인 API는 아직 비활성이다.

## API 소비자 대조 결과

검토한 ON 입력·응답·오류의 범위에서 새로운 method/path/body/envelope 불일치는 발견하지 않았다.

- LOCAL/role 포함 Google은 기존 합성 DTO 필드와 동일한 body를 보낸다. 최초 Google token-only 409를 추가 가입 성공이나 재시도로 바꾸지 않는다.
- status는 본인 Bearer wrapper와 no-store를 사용하고 세 상태/required/null verifiedAt을 검증한다. 필수 응답 필드가 빠지거나 타입이 잘못되면 INVALID_RESPONSE로 처리한다.
- request는 body 없이 본인 Bearer POST다. 202/null은 접수이며 module은 확인 상태를 만들지 않는다. confirm은 `{token}` 공개 POST, `credentials:'omit'`, no-store/no-referrer이며 토큰 계정 응답을 현재 세션에 적용하지 않고 반환하지 않는다.
- 일회 사용·만료·bad format의 합성 오류, Retry-After 없는 429, status 401, JSON/raw 업무 403이 오류로 유지되며 자동 재시도하지 않는다. 이 검사는 서버의 토큰 소비·메일 전송을 실행한 증거가 아니다.
- policy summary의 type/version/title/summary/requiresConsent와 `/api/policies/current`, `/{type}/{version}` 연결은 BE DTO/controller와 source로 일치한다. required 대상 0개에 `[]` 전송이 가능하고 FE hook의 빈 목록 complete 식도 확인했다. 정책 실제 게시 상태·가입 동의 설정의 운영 선택은 확인하지 않았다.
- 미래 날짜 제한·나이/guardian 승인을 추가로 추정하지 않았다. 최신 FE의 달력 날짜 검증이 미래의 유효 날짜를 허용하는 것을 실행 확인했다.

핵심 근거는 최신 [authRepository](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/features/auth/authRepository.ts), [이메일 repository](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/features/auth/emailVerificationRepository.ts), [readiness와 상태 함수](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/features/auth/launchAuthContract.ts), [가입 정책 hook](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/features/auth/useSignupContract.ts), [공개 확인 화면](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/app/pages/VerifyEmailPage.tsx), [AuthProvider](https://github.com/AutoAI-UTEUM/FE/blob/5e91b5789daa13b3a4923653285709d18f232c79/src/features/auth/AuthProvider.tsx)다.

## readiness와 남은 인수

현재 FE의 정확한 ON 키는 `VITE_AUTH_CONTRACT_READINESS=be-auth-ee69e425-v1`다. 누락·빈 값·다른 문자열은 OFF이며 이 동작을 직접 실행했다. 이 값은 **FE 빌드 설정**으로 BE의 공개 capability/revision 응답이 아니다. `ee69e425`라는 문자열, 동일한 main-service tree 또는 문서/CI 성공도 실제 실행 artifact의 SHA·migration·FE 배포 설정·메일 준비 합의를 대신하지 않는다. BE 공개 신호의 필요성과 방식은 [후속 제안](FE-FOLLOWUP.md)의 미합의 상태를 유지한다.

FE PR229의 Vitest1003·합성 E2E17 및 dev 반영 결과는 [FE PR의 보고](https://github.com/AutoAI-UTEUM/FE/pull/229)다. 이 세션에서 FE 전체 build/Vitest/Chromium이나 실제 dev 환경을 다시 실행·조회하지 않았다. 기본 OFF의 신규 가입 body 누락·이메일 API 비활성은 런타임 gate 완화로 해결하지 않는다.

최종 후보/FE head·실행 artifact 및 환경별 readiness·현재 정책 API/migration·메일 운영 담당/provider/실수신·유효 FE link origin의 합의와 실환경 인수가 남았다. 배포/기능 활성화는 승인되지 않았다. 부모가 지시한 Nginx 직접 진입/접근 로그 진단은 통합 담당의 별도 범위이며 이 작업은 해당 코드 수정·실사이트 GET·토큰 접근 로그 조회를 수행하지 않는다. 운영 순서와 rollback은 [기존 preflight](../../launch-deployment-preflight.md)를 재사용한다.

## 경량 검증 재현 및 한계

설치 없이 Node24.12.0에서 두 checkout을 지정한다. 원 baseline용과 최신용 환경 변수는 구분한다.

```powershell
$env:FE_AUTH_SOURCE_ROOT = 'C:\Users\russe\Documents\Codex\2026-10-04\task\FE-readonly'
$env:FE_AUTH_CURRENT_SOURCE_ROOT = 'C:\Users\russe\Documents\Codex\2026-10-04\task\FE-pr229-readonly'
node --disable-warning=ExperimentalWarning --test --test-reporter=spec docs/qa/fe-auth-contract/contract.test.mjs docs/qa/fe-auth-contract/consumer.test.mjs docs/qa/fe-auth-contract/followup.test.mjs docs/qa/fe-auth-contract/consumer-pr229.test.mjs
```

결과 **50/50 passed, failure0/skip0**: BE source/fixture/link12 + 원 FE 소비자17 + 최신 FE ON/OFF source/소비자21. KNOWN GAP5 통과는 과거 checkout의 누락 재현이며 최신 결과와 혼동하지 않는다. 두 head/tracked-clean 검사가 각각 실행된다.

기존 소비자 파일의 공통 API 검사도 최신 FE에서 다시 실행해 **11/11 passed, failure0/skip0**를 확인했다. 이 호출은 원 head/KNOWN GAP 검사 6개를 선택하지 않는다. 최신 FE 검증은 새21 + 기존 공통 API11이며 50개 전체와 구분한다.

```powershell
$env:FE_AUTH_SOURCE_ROOT = $env:FE_AUTH_CURRENT_SOURCE_ROOT
node --disable-warning=ExperimentalWarning --test --test-reporter=spec '--test-name-pattern=existing generic client|confirm success then|GET confirm|429 request|401 status|raw file|Google email conflict|malformed DOB' docs/qa/fe-auth-contract/consumer.test.mjs
```

harness는 실제 FE TypeScript 모듈을 Node transform/VM에서 실행한다. 기존 module wiring/getApiBaseUrl/mock fetch에 더해 Vite의 `import.meta.env`만 합성 값으로 공급한다. 실제 isLaunchAuthReady·builder·mapper·이메일 repository 함수는 FE 소스 그대로다. 서버 socket·실HTTP·쿠키 jar·React/router 렌더·경쟁 상태·메일/provider 동작은 실행하지 않는다. source-only 검사는 route/UI/정책/403 연결 근거이며 브라우저 인수와 구분한다.

BE runtime/common tests·migration/defaults·빌드 설정과 FE 소스는 수정하지 않는다. 변경은 `docs/qa/fe-auth-contract/`의 문서·격리 검증뿐이며 실Google/메일/SMS/실계정/DEV DB·배포·외부 메시지는 실행하지 않았다.
