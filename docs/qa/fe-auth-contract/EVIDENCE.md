# FE auth 계약 검증 증거

기준은 [전달 계약](README.md)의 exact BE/FE head다. 변경은 이 디렉터리의 문서·fixtures·Node 검증에만 한정한다. `main-service`, 기존 tests, 빌드 설정, migration, 정책 defaults, 실제 FE 파일은 변경하지 않는다.

## 이번 실행

| 항목 | 결과 / 해석 |
| --- | --- |
| 원격 head 확인 | 최신 PR495 `ee69e4259b1b80871fdc1805a7b42d8628ac57e9`; BE develop `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`; FE develop `1b6987d8472a064a080a00bd43232993e9bd41eb` |
| 부모 갱신 반영 | 최초 `b6f4f5f`에서 최신 PR495로 fast-forward. 차이는 AI 테스트 자료 3개/394 추가 행이며 `main-service` tree가 같음. PR501 `fd12b01ecfef34b45629e2a8efe4068a4b5861c1` 배포 문서는 [exact 링크](https://github.com/AutoAI-UTEUM/BE/blob/fd12b01ecfef34b45629e2a8efe4068a4b5861c1/docs/launch-deployment-preflight.md)로 재사용함. |
| 지침 | root `AGENTS.md`, API/화면/에러/도메인/DB 관련 절, 협업·PR·Definition of Done·테스트 지침 확인. 이 checkout에 추가 AGENTS/.agents skill 없음. |
| 경량 명령 | `node --disable-warning=ExperimentalWarning --test docs/qa/fe-auth-contract/contract.test.mjs docs/qa/fe-auth-contract/consumer.test.mjs` (`FE_AUTH_SOURCE_ROOT` 지정) |
| 검증 결과 | Node 24.12.0에서 **25/25 통과, 실패 0, skip 0**. BE source/fixture/link 8개 + 실제 FE consumer/mock 17개. 소비자 중 KNOWN GAP 5개는 누락을 재현한 성공이며 FE 인수 성공이 아님. |
| 변경 범위·whitespace | staged `git diff --check` 통과. 최신 base 대비 새 파일 6개 모두 `docs/qa/fe-auth-contract/`. main-service tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9` 불변, FE tracked diff 없음. |
| 로컬 무거운 실행 | Gradle 전체 build/기존 Spring tests, Docker/MySQL, AI suite 실행하지 않음. 병렬 기존 구현 세션의 자원·검증 담당 범위를 유지함. |
| 실환경 실행 | 실Google·메일·SMS·유료 AI·실계정 생성·실DEV DB·학생 데이터·배포·FE 화면/브라우저 E2E 모두 미실행. |

초기 소비자 실행에서 실제 FE 코드 15개 검사는 통과했고 head 검사 1개는 Windows sandbox 하위 프로세스의 Git 소유자 차이로 실패했다. 명시적으로 선택한 읽기 전용 clone만 신뢰하는 `git -c safe.directory=<선택 경로>`로 검사 호출을 수정했다. 글로벌 Git 설정·FE 파일은 바꾸지 않았다. 이 초기 결과를 최종 성공 결과로 합산하지 않는다.

첫 전체 실행은 consumer 17개 + source 7개가 통과하고 generic Java record(`ApiResponse<T>`) 파서 1개가 실패했다. 검증 파서를 generic record에 맞게 수정한 후 위 최종 25개가 통과했다. 제품 소스 수정은 없었다. 오류 상태/안내 문자열도 현재 ErrorCode에 대조했다. 테스트 결과 집계는 최종 실행 한 번의 결과다.

## 현재 FE 소비자에서 재현한 차이

아래 결과는 실제 TypeScript body/mapper/client에 synthetic fetch를 연결해서 관찰한다. mock 응답을 고정하므로 실제 BE가 응답했다는 증거가 아니다.

| 관찰 | 소스 / 영향 |
| --- | --- |
| LOCAL builder에 DOB를 넘겨도 요청에서 사라짐 | FE `src/features/auth/authRepository.ts` `signup`. 신규 BE 입력과 불일치, `VALIDATION_FAILED` DOB 상세도 FORM_FIELDS에 없어 폼 필드로 매핑되지 않음. |
| Google 신규 재요청에도 DOB·consents가 사라짐 | 동일 파일 `loginWithGoogle`. 기존 subject 로그인은 가능하나 신규 role 제출만으로는 `SIGNUP_REQUIRED`가 해소되지 않음. |
| 로그인/me/Google mapper가 이메일 상태 3필드를 버림 | 동일 파일 `mapUser`, `src/features/auth/authContext.ts` AuthUser. 새 계정의 업무 403 및 기존 예외를 상태로 안내할 수 없음. |
| `/verify-email`과 3개 API 연결 없음 | FE `src/app/routes.ts`, `src/app/AppRoutes.tsx`, SignupPage/타입, AuthRepository. source 검사이며 React 화면 실행은 아님. |
| 일반 JSON/raw client는 새 envelope·202/null·401/403/429/405를 읽음 | 기존 FE `apiClient.ts`, `rawApiClient.ts`, `rateLimitError.ts`, `contracts.ts`. 호출 연결·UI·인증 상태 반영은 별도 구현 필요. |

## 기존 서버 검증을 재사용한 범위

다음 테스트 **소스**를 읽었다. 이번 세션에서 실행하지 않았고 결과 숫자를 새로 주장하지 않는다. PR495의 이전 전체/통합 실행 기록은 [원래 PR](https://github.com/AutoAI-UTEUM/BE/pull/495)에 있다. 이 문서 브랜치의 runtime 실행으로 옮겨 적지 않는다.

- [EmailVerificationApiIntegrationTest](../../../main-service/src/test/java/io/edupilot/auth/EmailVerificationApiIntegrationTest.java): LOCAL link/PENDING, 익명 request/status 거부와 GET 비소비, 재발급 이전 링크/확인 후 no-op, 만료/이메일 불일치, 탈퇴, 동시 확정 1성공, rollback, 현재 DB gate 및 업데이트/탈퇴 경합.
- [EmailVerificationTokenTest](../../../main-service/src/test/java/io/edupilot/auth/EmailVerificationTokenTest.java): strict expiry·1회 사용·이메일 바인딩.
- [GuardianFoundationJpaTest](../../../main-service/src/test/java/io/edupilot/guardian/GuardianFoundationJpaTest.java): LOCAL 신규 DOB 필수, 신규 Google DOB·기존 subject DOB 불필요, 필수 동의 유지, 입력만으로 승인 없음.
- [LegacyAccountAccessJpaTest](../../../main-service/src/test/java/io/edupilot/auth/LegacyAccountAccessJpaTest.java): legacy UNKNOWN 이용 유지, 신규 UNKNOWN/DOB누락/오래된 생성시각 예외 금지, 타인 리소스·정지/탈퇴 거부, 확인 근거 보존.
- [LegacyAccountAccessMigrationTest](../../../main-service/src/test/java/io/edupilot/guardian/LegacyAccountAccessMigrationTest.java): V58 기존/신규 cohort 구분. 실제 적용 DB를 이 문서에서 추정하지 않음.

## 부모/기존 구현 담당 전달 사항

아래 차이는 원래 검토 head `d0047567c2864e999e1c5bac781a39ac43de8a3e`에서 발견한 기록이다. 최종 통합 담당의 `feature/479-auth-doc-alignment`는 기존 API/DOB/이메일/DB/화면 문서를 현재 DTO·V58·PolicyServiceTest에 맞춰 수정한다. runtime·정책 defaults·migration은 변경하지 않으며 원래25개 결과를 새 runtime 실행 증거로 바꾸지 않는다.

이 작업의 검토 범위에서 새 runtime 결함을 확정한 것은 없다. 확인한 **기존 문서와 코드/테스트의 차이**는 다음과 같으며 공통 파일 수정은 기존 구현 담당에게 맡긴다.

1. [api-spec.md](../../api-spec.md) LOCAL 가입 및 Google 추가 정보 JSON 예시가 DOB를 빠뜨린다. 실제 [SignupRequest](../../../main-service/src/main/java/io/edupilot/auth/dto/SignupRequest.java) 및 [GoogleAccountService](../../../main-service/src/main/java/io/edupilot/auth/GoogleAccountService.java)는 신규 DOB를 요구한다. 이 전달 문서는 corrected synthetic 예시를 제공한다.
2. [email-verification.md](../../email-verification.md)의 기존 UNKNOWN 차단/운영자 재확인 문단과 [database.md](../../database.md)의 V55 설명은 초기 단위 설명이다. 후보 V58·[User.isEmailVerificationRequired](../../../main-service/src/main/java/io/edupilot/user/User.java)·legacy 문서에서는 기존 예외 이용을 유지한다. 부모의 최종 문서 통합에서 초기 설명에 V58 이후 경계를 붙여야 한다.
3. [PolicyService.java](../../../main-service/src/main/java/io/edupilot/policy/PolicyService.java)의 `validateSignup`은 required 정책 목록이 비면 설정 검사 전에 no-consent selection을 반환한다. [PolicyServiceTest](../../../main-service/src/test/java/io/edupilot/policy/PolicyServiceTest.java)의 `mandatorySettingDoesNotBlockSignupWhenNoDocumentRequiresConsent`도 이 동작을 명시한다. `api-spec.md`의 “true일 때 문서가 하나 이상 게시돼 있어야 함” 문장과 차이가 있으나 테스트에 고정된 현재 동작이므로 여기서 결함으로 확정하거나 defaults를 변경하지 않는다.

현재 FE 불일치는 BE를 완화해서 해결하지 않는다. FE 담당이 DOB·정책 입력 전달, 이메일 상태 보존, 공개 확인 route/수동 POST, 인증된 status/request, 403 공통 처리, 기존 이용 예외 및 탭/계정 전환 인수를 수행해야 한다. 연령/보호자 정책·전역 연결 미완료는 [기존 기반 문서](../../birthdate-guardian-foundation.md)의 후속 범위이며 이메일 성공으로 대신하지 않는다.

## 2026-10-04 후속 계약 질의

- [FE-FOLLOWUP.md](FE-FOLLOWUP.md)에 부모 전달용 짧은 답변, policy 0개·V58·DOB 예시 정정 확인, 실제 메일 담당/준비 및 유효 link origin, 현재 capability 부재와 미합의 제안을 정리했다. 외부 전송은 수행하지 않았다.
- 읽은 후보 snapshot은 `dbd148352753e2b52f8f9cbf8599c5024c61dd8d`다. PR502와 부모의 공통 문서 정정 `2156757`이 포함됐다. 위 과거 발견 기록의 문서 차이 세 가지가 현재 공통 문서에 정정된 것을 확인했다. 최종 후보/배포/FE 활성화 합의를 완료했다고 표시하지 않는다.
- 원격 재확인 FE develop은 `1b6987d8472a064a080a00bd43232993e9bd41eb`로 동일하다. 최신 읽기 전용 소스의 VITE_API_CAPABILITIES는 FE 빌드 설정이며 auth BE capability 응답이 아니다. 후속 구현 예정 메시지의 공개 커밋은 확인되지 않았다. 실제 서버/배포 FE build는 조회하지 않았다.
- `FE_AUTH_SOURCE_ROOT` 지정 후 `node --disable-warning=ExperimentalWarning --test docs/qa/fe-auth-contract/contract.test.mjs docs/qa/fe-auth-contract/consumer.test.mjs docs/qa/fe-auth-contract/followup.test.mjs`: **29/29 통과, 실패0/skip0**. source/fixture/link12 + 기존 실제 FE 소비자17이며 KNOWN GAP5는 누락 재현으로 유지했다.
- 이 후속 변경은 전용 경로의 문서·source 검사 5개 파일에만 한정한다. staged diff --check 통과, main-service tree 불변, FE tracked diff 없음. Java/Gradle·Docker/MySQL·실Google/메일/SMS·실계정/DEV DB·설정 변경·배포·FE 후속 화면 E2E는 실행하지 않았다.
- 런타임 담당의 후속 검토가 필요한 것은 공개 auth capability/revision 신호의 계약·build revision 근거·구형 404 처리와 rollout 합의, 동의 대상이 한 종류일 때도 두 종류를 모두 요구하는 듯한 현재 POLICY_CONSENT_REQUIRED 안내 문구다. 실제 오류 문자열/fixtures·정책 defaults·runtime은 바꾸지 않았다.

## FE PR229 develop 반영 이후 재확인

- [FE-PR229-REVIEW.md](FE-PR229-REVIEW.md)에 최신 소비자 결과를 분리했다. FE merge `5e91b5789daa13b3a4923653285709d18f232c79`, PR head `43467a8343053e41f48d9667469dc110ceb32018`, BE 선택 base `4e578c5f811d0084de0b80ee72b5ed7c72ba84c6`를 git 원격/GitHub PR로 확인했다. BE main-service tree는 동일한 `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`다.
- 초기 Git fetch의 자동 승인 검토 시간 초과와 일시 연결 실패 후 exec 연결 복구를 확인하고 읽기 fetch에 성공했다. 원 FE clone을 reset/편집하지 않고 별도 detached worktree `FE-pr229-readonly`에서 읽었다.
- 기존 KNOWN GAP5는 최신 readiness ON 코드에서 보완됐다. 이메일 필드 보존은 OFF에서도 작동한다. default OFF의 DOB/consents 누락·이메일 HTTP 호출 전 거절은 별도 실행 재현했고 최종 후보/BE capability·활성화·실메일 합의는 미완료로 유지했다. 범위 내 ON method/path/body/envelope/error의 새 불일치는 발견하지 않았다.
- 원 `consumer.test.mjs`/fixtures의 FE baseline SHA와 과거 발견 기록은 보존했다. helper에 실제 최신 module/env 공급을 추가하고 `consumer-pr229.test.mjs`·`pr229-review.json`에 독립 최신 검증을 고정했다. 실제 FE 소스 수정·의존성 설치·Nginx 중복 진단은 하지 않았다.
- 위 재현 명령은 **50/50 passed, failure0/skip0**: BE source/fixture/link12 + 원 FE 소비자17 + 최신 FE ON/OFF source/소비자21. 최신 FE의 50개 인수라고 표시하지 않는다. React/브라우저 경쟁 상태·실HTTP/쿠키·실Google/메일/SMS·실계정/DEV DB·배포는 미실행이다. FE의 Vitest1003/E2E17은 FE PR 보고이며 이 세션 실행 결과가 아니다.
- 기존 공통 API 검사도 최신 FE에서 양성 이름 필터로 **11/11 passed, failure0/skip0** 재실행했다. 원 head/KNOWN GAP 6개는 이 호출에서 선택하지 않았다. 첫 제외형 필터는 의도와 달리 원 baseline까지 실행해 head guard와 과거 누락 기대 4개가 실패했다(12pass/5fail). 최신 소스에 과거 기대를 적용한 검사 선택 오류였으며 아래 정확한 양성 필터로 수정했다. 최신21 검사와 공통 API11의 최종 실행은 모두 통과했고 숨긴 runtime/FE 변경은 없다.

## 12:41 UTC 재개: FE231 설정과 fragment 경계

- [FE-PR231-SETTINGS.md](FE-PR231-SETTINGS.md)에 현재 settings wire 계약·새 실패 검사·보호 범위와 미합의 fragment 실패안을 정리했다. 선택 BE base `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`, main-service tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`, 별도 읽기 FE worktree HEAD `21f4ad2d30f13bafd05bfcc289515c2caac98810`을 확인했다. FE PR231 source head는 `3a9abe566d3926da69404192b35708a269c22ef6`다.
- 기존 FE231 성공/계정 전환/unmount 테스트는 읽고 새로 빠져 있던 저장 실패 후 재저장·서버 응답 snapshot·늦은 실패/same-owner generation·응답 불확실성·보호 범위 검사를 추가했다. 정상 BE settings 계약은 nullable 부분 PATCH/전체 GET·PATCH 응답이다. current FE 전체3필드 전송은 호환되지만 취소/화면 rollback은 DB rollback을 증명하지 않는다.
- 새 Node **20/20 passed, failure0/skip0**: settings15 + fragment5. repository/API client와 실제 저장 closure body/early script/parser를 합성 환경에서 실행했다. 첫 실행의19pass/1fail은 VM realm 객체의 deepStrictEqual 비교 문제였고 plain JSON 정규화로 수정했다. FE/BE runtime 수정으로 해결한 실패가 아니다.
- fragmentAgreement=pending을 유지한다. 현재 FE의 fragment-only 미지원·query/fragment 혼합의 query 우선·중복/형식 실패 fallback 부재를 bootstrap/client navigation 양쪽에서 재현했고 미래 fragment 지원을 통과로 표시하지 않았다. 운영자의 답변/활성화·형식 전환·실제 서버 로그/메일 인수는 미완료다.
- 추가 검토 경계는 profile save가 preference owner guard 밖인 callback 관찰, malformed2xx preference data의 repository 검증 부재, 응답 유실 뒤 옛 전체 payload 재사용이다. 정상 BE가 잘못된 data를 반환하거나 다른 계정 DB를 썼다는 결과로 확대하지 않는다. FE 담당 검토 대상으로 전달하고 실제 FE 파일은 수정하지 않는다.
- 변경은 전용 docs/qa/fe-auth-contract 경로의8개 파일이며 기존 공통 문서/runtime/tests/migration/defaults/Nginx/workflows를 보존했다. 실제 API/학생/Google/메일/SMS/유료AI·DEV DB·Java/MySQL/FE 전체 quality/브라우저/배포·공개 상세 댓글·Discord는 미실행이다. 이전90/50 검사나 FE231 작성자의1013 테스트 보고와 합산하지 않는다.

## FE232 회신 이후: 지원 구현과 BE 전환의 구분

- [FE-PR232-FRAGMENT.md](FE-PR232-FRAGMENT.md)에 최신 FE source와 소비자 결과를 추가했다. FE merge/보고된 DEV `5592042cb7a25c2fc797e557a20643061b84e273`, PR head `114f4cf4bd13ef531f7396747ef1abdb6e104804`를 별도 읽기 worktree로 확인했다. 선택 BE base/runtime은 `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e` 그대로다.
- PR509의 FE231 기록·settings-fragment-review.json/pending/기존5개 기대는 유지하고 역사적 검사 제목·최신 안내 링크만 보완했다. FE232는 canonical fragment-only를 구현하고 구 query/혼합을 거부한다. 현재 지원을 이전 미지원으로 부르지 않으며 BE 공동 전환·실메일·활성화는 완료로 표시하지 않는다.
- 새 source/소비자14와 공유 token harness 회귀인 역사적5를 **19/19 pass, failure0/skip0**로 실행했다. actual page action/layout/mount callback·parser·BE 합성 envelope를 결합해 초기 scrub/중복 클릭/공개POST·현재계정재조회/reload/pagehide·SPA교체/늦은성공/OFF/무효/429/구링크202 경계를 확인했다. 실제 React/브라우저/BFCache 실행은 아니다. FE232의1042/ON18/OFF8·dev37203882484는 작성자 보고로 구분한다.
- 범위 내 새 FE 결함은 확정하지 않았다. core 전달 근거는 EmailVerificationService.java86의 query 발급과 FE232 거부의 호환 차이, EmailOutboxStore.java36–40/81의 완성 message encrypt/기존 payload decrypt에 따른 대기 본문 전환 검토 필요다. 실제 runtime을 수정하거나 실제 대기함/메일/계정/DB를 조회하지 않는다.
- 문서 링크 검사 **1/1 pass, failure0/skip0**와 diff --check를 통과했다. 최신 FE232·역사적 FE231 worktree는 clean이고 BE main-service tree는 `20a4c0c7c55b44d8b792ce282e622eddb4d573d9` 그대로다. 이번 추가분은 전용 경로10개 파일, PR 누적은12개 파일이며 exact head와 그 원격 CI는 draft PR509에 따로 기록한다.
