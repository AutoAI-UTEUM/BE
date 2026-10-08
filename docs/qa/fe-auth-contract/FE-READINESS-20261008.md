# FE 출시 계약 읽기 점검과 담당자 인계 — 2026-10-08

BE [draft PR541](https://github.com/AutoAI-UTEUM/BE/pull/541) `58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e`와 그 안에 통합된 [PR538](https://github.com/AutoAI-UTEUM/BE/pull/538) `cca982c5a1663ee7d474446390618c371b151806`의 계약을 FE develop `f1ad9d924b4768f798dc438066a311a2aac955f0`와 대조했다. 이 문서는 FE 담당자가 구현과 인수 증거를 준비하도록 돕는 BE 문서다. FE 수정·빌드·배포·담당자 메시지·서버/DB 변경·실메일은 실행하지 않았다. 오늘 DEV 공개 GET은 인증·쿠키 없이 홈, entry asset, 현재 정책의 **3회**이며 브라우저 UI는 실행하지 않았다.

## 지금 확인한 근거와 확인 범위

| 대상 | 2026-10-08 확인 결과 | 이 결과가 증명하는 범위 |
| --- | --- | --- |
| FE develop | `f1ad9d924b4768f798dc438066a311a2aac955f0` 유지 | 아래 소스 비교의 정확한 기준 |
| [Frontend CI37218154399](https://github.com/AutoAI-UTEUM/FE/actions/runs/37218154399) | 정확 head의 5개 job SUCCESS | 기존 lint/typecheck/test/build 및 합성 브라우저 검사 성공. PR538/541의 새 계약을 검증했다는 뜻은 아님 |
| 위 CI의 launch-auth-contract | `synthetic-ready` 18 PASS, `default-off` 8 PASS | localhost·mock API·서로 다른 QA 빌드 입력의 기존 계약. 실제 DEV 설정·TEAM 인수와 구분 |
| [DEV deploy37218154373](https://github.com/AutoAI-UTEUM/FE/actions/runs/37218154373) | 정확 head SUCCESS; build 및 1043개 기존 테스트 실행 성공; smoke `/assets/index-BtTKlmQm.js` | 당시 빌드·복사·공개 asset smoke 성공 |
| [DEV workflow](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/.github/workflows/deploy-fe-dev.yml) | `VITE_AUTH_CONTRACT_READINESS` 입력 선언 없음. 배포 job log에도 없음 | 실제 입력 원문은 **NOT_ATTESTED**. 다른 QA job의 입력으로 대신 증명하지 않음 |
| 06:44:46 UTC 실제 entry | `/assets/index-BtTKlmQm.js`, SHA-256 `2d5bfdefda9c0687dd145f74708c0bfc8e53d318142d88baa1065f534e5090e8` | 10-07 관측 hash와 같음. 아래 식으로 이 파일의 readiness OFF를 정적으로 확인 |
| 06:44:46 UTC `/api/policies/current` | TERMS0.9·PRIVACY0.9 모두 `requiresConsent:false`, `effectiveAt:2026-09-25T17:26:55.146860Z`; 필수 목록0개 | 현재 공개 정책 metadata 관측. 검토 중인 1.0 게시·필수 선택 완료를 뜻하지 않음 |
| 실제 DEV 정상 UI·TEAM 인수 | **NOT_RUN** | 소스·mock·GET 성공으로 대체하지 않음 |

실제 entry의 다음 식을 발견했다. `s9`는 고정 문자열이고 `void 0`은 undefined이므로 `o6()`는 이 관측 파일에서 false다. 주변의 이메일 상태·이용 제한 helper, LOCAL 요청의 `...o6()?{dateOfBirth,consents}:{}` 및 export가 [소스 readiness helper](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/features/auth/launchAuthContract.ts#L2)와 대응한다. **STATIC_FALSE_AT_OBSERVED_ENTRY**라는 정적 판정이며 JavaScript 실행·브라우저 인수·build 입력 원문 확인은 아니다. 기존 기록의 문자열 존재 검사보다 근거를 좁혀 보강한 것으로, 다른 chunk·캐시·사용자 세션의 동작을 보장하지 않는다.

```js
const eN = W0(B1), s9 = "be-auth-ee69e425-v1";
function o6() { return s9 === void 0; }
```

현재 소스의 ON 조건은 정확한 `VITE_AUTH_CONTRACT_READINESS === 'be-auth-ee69e425-v1'`이다. 이 기존 이름은 TEAM 구현이나 운영 승인 증거가 아니다. 이 문서를 읽었다는 이유로 flag를 켜거나 signup·보호자 게이트를 완화하지 않는다.

## PR541·PR538와 현재 소비자의 차이

소스30개를 immutable FE head에서 읽었다. 아래의 미연결 판단은 명시한 auth·요청·route·설정 소스와 전체 경로 목록을 기준으로 한다. 원격 화면 조작이나 전체 FE 실행은 하지 않았다.

| BE 계약 | 읽은 FE 근거 | FE 담당자가 준비할 변경·확인 |
| --- | --- | --- |
| 필수 정책0개 + signup-consent-required=true → `SIGNUP_POLICY_NOT_READY` **503** | [SignupPage](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/app/pages/SignupPage.tsx#L216)의 LOCAL216·Google289 분기는 기존 `POLICY_CONSENT_REQUIRED`만 처리. 새503은 LOCAL227·Google298 일반 오류로 이동 | 준비 중 안내·신규 가입 보류를503 전용으로 처리. 입력 잘못/동의 누락/가입 성공으로 표시하지 않으며 자동 반복 제출하지 않음 |
| 필수 정책이 있고 선택 누락·중복·버전 불일치 → `POLICY_CONSENT_REQUIRED` **400** | LOCAL·Google 모두 최신 정책 reload와 재동의가 있음 | 기존400 처리 유지. 새503과 구분한 합성 화면 증거 추가 |
| `requiresConsent`가 필수 범위를 결정 | [useSignupContract](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/features/auth/useSignupContract.ts#L37)의 `documents.every(...)`는 빈/notice-only 목록도 complete로 평가. 기존 합성 가입 검사는 빈 선택 요청을 허용 | 빈 필수 집합을 준비 완료로 보지 않게 UI를 보완. 한 종류만 필수인 기존 계약은 보존; 두 문서·두 필수 선택을 임의 강제하지 않음 |
| LOCAL·신규 Google의 DOB/current version, 기존 Google subject 로그인 보존 | [authRepository](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/features/auth/authRepository.ts#L145)는 ON일 때 LOCAL/신규 Google의 DOB·consents를 전송. OFF이면 생략 | ON/OFF와 신규/기존 Google을 분리해 검증. FE가 나이나 승인 범위를 생성하지 않음 |
| PENDING 본인 `/guardian-requests/entry` 조회 및 본인 `/withdraw` 예외 | [isAccountManagementRequest](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/features/auth/launchAuthContract.ts#L29)에 보호자 경로가 없음. [AuthProvider](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/features/auth/AuthProvider.tsx#L890)는 이메일 확인 대기 중 해당 API를 호출 전403으로 막음 | TEAM 연결 시 BE가 허용한 정확한 경로·메서드를 대조. 학습·파일·SSE·외부 AI 게이트를 넓히지 않고 최초 조회/취소를 제공 |
| 서버 `/entry` 대상 판정; TEAM 미준비 시 수집 중단 | 현재 [routes](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/app/routes.ts#L1)·auth/settings repository에 TEAM route/DTO 호출이 보이지 않음 | DOB·브라우저 연도로 대상 추론하지 않고 Entry 사용. 불가 상태에서 정보 입력·POST를 열지 않음 |
| `replyChannel`, admin Detail `generationStartedAt`·`declaredScopes` | 읽은 FE auth·설정·요청·화면 소스에 이 필드 소비가 없음 | 양식 문자열 파싱 대신 typed 필드 사용. 관리자 전용 필드를 본인·공개 화면에 복사하지 않음 |
| 이메일 fragment의 명시적 confirm·재발급 | [VerifyEmailPage](https://github.com/AutoAI-UTEUM/FE/blob/f1ad9d924b4768f798dc438066a311a2aac955f0/src/app/pages/VerifyEmailPage.tsx#L86)·emailLinkToken에 기존 연결 및 합성 CI 근거가 있음 | 실제 승인된 빌드에서 UI 인수 증거 제공. GET/mount/prefetch 확인 POST 없음, 202/null은 발송·수신 완료 아님 |

## TEAM 연결 때 지킬 version·null·차수 계약

전체 필드는 [TEAM FE 계약](../../guardian-team-review-fe-contract.md)에 있다. 이번 인계에서 강조하는 내용은 다음과 같다.

- `Entry.requirement`는 REQUIRED/NOT_REQUIRED/BIRTHDATE_REQUIRED다. `teamReviewAvailable:false`의 `request:null`은 과거 신청 부재를 증명하지 않는다. `canStartRequest`는 조회 시점 신호이며 POST 성공·이용 승인 신호가 아니다.
- `replyChannel`은 Entry·View·Detail에서 읽는다. 오래된 안내 `currentNotice:false`의 채널·noticeUrl null, `forms:{}`를 다른 응답의 채널이나 이전 캐시로 채우지 않는다. EMAIL_REPLY ↔ confirmation EMAIL_REPLY, PHONE_CALLBACK ↔ PHONE을 정확히 대응한다.
- `Link.replayed:true`의 `url:null`, 비어 있는 UTC 시각, 파기된 이름·연락처 null을 지원한다. 최초 수집·신청·링크 만료·승인 기한을 합치지 않는다. 재발급이 신청 기한을 연장한다고 표시하지 않는다.
- `noticeVersion`은 불투명 문자열, noticeDigest는 서버가 제공한 값이다. `generation`·`revision`은 최신 응답을 사용한다. 다른 차수·409의 버전·본문을 FE에서 자동 교체해 재승인하지 않는다. 동일 작업만 같은 idempotencyKey·같은 본문으로 재시도한다.
- `Detail.generationStartedAt`은 담당자 전용 차수 시작 UTC이며 재발급으로 이동하지 않는다. `responseReceivedAt`을 현재 검토 시각으로 바꾸지 않는다. `declaredScopes`는 배열이며 빈 값은 `[]`; 선언/수동확인 출처는 상태와 `webDeclaredAt`도 대조한다.
- 웹 `DECLARED`·링크·체크·메일 도착을 APPROVED로 표시하지 않는다. SERVICE와 선택 EXTERNAL_AI를 분리한다. SERVICE 승인이 외부 AI나 이메일·역할·소유권 권한을 대신하지 않는다.
- pending/정책503/guardian403은 인증401과 구분한다. 철회·권한 회수·설정/안내409는 입력과 담당자 상세 캐시를 비우고 상태를 재조회한다. 회신 본문·연락처·토큰·비밀번호를 로그/URL/query/영구 저장소/증거에 넣지 않는다.

## FE 담당자 한 번에 반환할 증거

기존 승인인 아동 포함·TEAM_REVIEW·KST 연도 기준·정정 접수·PDF30일·미확인 연락처5일·지정 회신/검토 계정·발신자·메일4건/수신자·DEV 로그14일·ADMIN 완료를 다시 묻지 않는다. 오늘 원문 회신도 별도 승인 경로에서 진행 중이므로 중복 발송하지 않는다. 남은 정책 문구·기간·관계 판단은 정책 검토 담당자의 입력으로 구분한다.

1. FE 후보 exact SHA와 PR, 해당 SHA의 CI/job 링크·필수 실제 실행 결과를 반환한다. 추가할 새503/TEAM 검사는 FE 담당자의 localhost 합성 인수로 준비한다. 기존 18/8 mock 성공을 새 계약 완료로 복사하지 않는다.
2. 승인된 빌드의 공개 readiness 값만 골라 기록한다. 전체 env·secret·쿠키를 보내지 않는다. 실제 `npm run build` job/입력과 dist entry·관련 chunk SHA-256, served asset 경로·SHA-256·관측 UTC가 같은 빌드에 연결됨을 입증한다. hash 일치는 UI 완료의 대체가 아니다.
3. LOCAL·신규 Google: DOB/current consent, 정책400 재조회, 새503 보류, 빈/notice-only와 단일 필수 문서, existing Google token-only를 합성 화면·요청 계약으로 확인한다. 정합한 정책 준비 뒤에만 승인된 운영 인수를 진행한다.
4. TEAM: 미준비·신규/기존·legacy·DOB 누락 Entry, pending 본인 조회/취소, replyChannel/null, currentNotice/version/generation/revision/409, 관리자 전용 필드, SERVICE/EXTERNAL_AI 분리, 철회/만료/권한 회수를 확인한다. 합성 사례별 결과와 파일 참조만 반환한다.
5. 실제 정상 UI와 운영 인수는 별도 승인된 담당자가 기록한다. 이 준비의 서버·실메일 조작은0이다. 미실행은 NOT_RUN, 출처나 결과가 없는 값은 null/NOT_ATTESTED로 남긴다.

비민감 인계 manifest는 아래 구조로 준비한다. 각 `caseResults`는 합성 alias·상태·증거 파일만 담는다. raw request/response, 계정 주소·user/request ID, token/password/연락처/회신 본문은 제외한다. template의 null·false를 성공으로 채우지 않는다. 현재 확인값이 들어간 작업 증거와 빈 담당자 template은 로컬 `evidence/policy-review-20261008/fe-readiness/`에 따로 보존한다.

```json
{
  "schema": "fe-auth-team-handoff-20261008-v1",
  "status": "DRAFT_NOT_ATTESTED",
  "beCandidateSha": "58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e",
  "feCandidateSha": null,
  "fePrUrl": null,
  "ci": { "runUrl": null, "headSha": null, "requiredJobs": [] },
  "build": {
    "runUrl": null, "headSha": null,
    "publicReadinessValue": null, "inputEvidenceRef": null,
    "distEntry": null, "distEntrySha256": null, "authChunks": []
  },
  "served": {
    "observedAtUtc": null, "entryPath": null, "entrySha256": null,
    "authChunks": [], "compiledReadinessEvidenceRef": null
  },
  "browser": {
    "syntheticContractStatus": "NOT_RUN",
    "normalApprovedUiStatus": "NOT_RUN",
    "caseResults": [], "evidenceRefs": []
  },
  "contractCoverage": {
    "signup503SeparateFrom400": null,
    "serverGuardianEntry": null,
    "pendingSelfEntryAndWithdraw": null,
    "replyChannelAndNulls": null,
    "noticeVersionGenerationRevision": null,
    "adminGenerationAndDeclaredScopes": null,
    "serviceAndOptionalAiSeparate": null,
    "revokeExpiryAndAuthorityLoss": null
  },
  "containsPersonalDataOrCredentials": false,
  "thisPreparation": {
    "feRepositoryWrites": 0, "feBuilds": 0, "deployments": 0,
    "serverMutations": 0, "mailProviderCalls": 0, "messagesSent": 0
  }
}
```

## 이번 변경의 검증

문서·manifest 정합성, 링크·고정 ref·artifact hash를 로컬에서 검증한다. FE/BE runtime 코드는 변경하지 않았고 Gradle·Node·FE CI·브라우저 테스트를 다시 실행하지 않았다. 위 테스트 숫자는 GitHub의 기존 exact-head 실행 로그에서 읽은 결과다. 실제 FE 후보 구현, approved build/artifact 및 정상 UI 증거, 검토 정책·운영 전환·복구 준비는 남아 있다.
