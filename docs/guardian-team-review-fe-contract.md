# 보호자 팀 확인 FE 연동 계약

유료 인증업체·SMS 없이 진행하는 `TEAM_REVIEW`의 BE 계약이다. FE 저장소는 이번 작업에서 수정하지 않는다. 동의 화면·담당자 화면·메일 회신 운영은 실제 환경 입력과 별도 활성화 승인이 준비된 뒤 연결한다. 기존 `/api/auth/guardian-verification/**` 웹/SMS API와는 별도 경로이며, 업체가 없는 기존 API를 성공으로 처리하지 않는다.

## 화면 흐름과 문구

| 화면 | 권장 문구 | 다음 행동 |
| --- | --- | --- |
| 본인 신청 | “보호자 확인 신청을 접수합니다. 담당자 확인이 끝날 때까지 해당 이용은 제한됩니다.” | 개인정보 없이 접수 가능. 보호자 이름·연락처를 아동이 입력하면 출처를 명시 |
| 보호자 안내 | “동의 내용을 읽고 회신 양식에 성명·관계·동의 여부를 직접 적어 주세요. 링크나 웹 체크만으로 승인이 완료되지는 않습니다.” | 동의문 전체·필수/선택 안내·회신 양식 제공 |
| 의사 표시 접수 | “의사 표시가 접수되었습니다. 명시적 회신 또는 전화 확인과 담당자 검토가 필요합니다.” | 최종 승인 표시와 이용 허용 금지 |
| 검토 대기 | “담당자가 확인 자료를 검토하고 있습니다.” | 현재 상태 확인, 중복 신청·반복 전송 제한 |
| 보완 요청 | “안내된 항목만 보완해 주세요. 불필요한 개인정보나 신분증 원본을 보내지 마세요.” | 정해진 회신 창구 안내; 최초 수집·신청 기한 연장 없음 |
| 승인 | “담당자 검토 결과, 신청이 승인되었습니다.” | 서버가 반환한 범위와 다른 가입·이용 조건 확인 |
| 반려 | “신청이 반려되었습니다. 안내된 사유와 문의 창구를 확인해 주세요.” | 자동 예외·재승인 금지 |
| 철회 | “신청 또는 승인이 철회되었습니다.” | 승인 상태·진행 중 화면을 갱신하고 제한 유지 |
| 만료 | “신청 또는 승인의 유효기간이 지났습니다.” | 현재 상태 조회 후 별도 새 절차 안내 |
| 비활성 | “보호자 팀 확인 절차를 준비하고 있습니다.” | 입력·전송 버튼 비활성, 보호자 정보를 미리 수집하지 않음 |

아동 이름·생년월일·연락처를 보호자 링크에서 보여 주지 않는다. `requestId`로만 대상을 연결하며 이 번호는 관계 확인 증거가 아니다. 재신청에서 같은 UUID를 사용하므로 안내·회신 양식의 `신청 차수`(`generation`)도 항상 함께 표시하고 담당자가 회신의 차수를 현재 신청과 대조하게 한다. 과거 차수 회신을 새 동의로 표시하지 않는다. 필수 동의와 외부 AI 선택 동의는 각각 선택하게 하고 체크를 미리 채우지 않는다. 선택 동의를 하지 않았다는 이유로 해당 범위에 동의가 있었다고 처리하지 않는다.

## API 경로

성공은 `ApiResponse.data`를 사용한다. 모든 응답에 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`가 적용된다. 여기서 `{id}`는 서버가 발급한 신청 UUID이며 이용자 ID를 대신 입력받지 않는다.

| API | 인증·권한 | 요청 | 결과 |
| --- | --- | --- | --- |
| `GET /api/users/me/guardian-requests/entry` | 활성 로그인 본인; 이메일·연령 확인 대기 중 조회 허용 | 없음 | `Entry`; 접수·정보 수집 없이 서버 대상 판정 |
| `POST /api/users/me/guardian-requests` | 활성 로그인 본인, 신규 보호자 대상 | `Intake` | `View` |
| `GET /api/users/me/guardian-requests` | 활성 로그인 본인 | 없음 | `View`; 접수 전은 404 |
| `POST /api/users/me/guardian-requests/{id}/link` | 해당 신청 본인 | `Mutation` | `Link` |
| `POST /api/users/me/guardian-requests/{id}/withdraw` | 해당 신청 본인; 이메일·연령 확인 대기 중 취소 허용 | `Mutation` | `Status` |
| `POST /api/auth/guardian-team/view` | 만료 전 링크 토큰 | `Token` | `View`; 조회는 토큰 소비 없음 |
| `POST /api/auth/guardian-team/consent` | 만료 전 링크 토큰 | `Consent` | `Status`; 접수만 수행 |
| `GET /api/admin/guardian-requests?page=0&size=20` | 현재 DB `ACTIVE ADMIN` + 지정 담당자 | page 0 이상, size 1~100 | `ListResponse` |
| `GET /api/admin/guardian-requests/{id}` | 현재 DB `ACTIVE ADMIN` + 지정 담당자 | 없음 | `Detail` |
| `POST /api/admin/guardian-requests/{id}/confirmation` | 지정 담당자, 결정 트랜잭션 권한 재확인 | `Confirmation` | `Status`; 수동 확인 등록 |
| `POST /api/admin/guardian-requests/{id}/decision` | 지정 담당자, 결정 트랜잭션 권한 재확인 | `Decision` | `Status` |
| `POST /api/admin/guardian-requests/{id}/revoke` | 지정 담당자, 결정 트랜잭션 권한 재확인 | `Revoke` | `Status` |

담당자 설정은 관리자 역할을 부여하는 API가 아니다. 관리자 역할만 있고 지정 목록에 없는 계정도 거절된다. 본인의 신청을 담당자로 승인하는 경로는 허용하지 않는다. 관리자는 FE의 이전 로그인 정보만으로 버튼을 활성화해 권한 검사를 대신할 수 없다.

### 최초 진입 신호와 회신 채널 (#526)

FE는 로그인 뒤 `/entry`를 조회하며 DOB·브라우저 연도·`ageVerificationState`로 신청 대상을 독자 추론하지 않는다. `Entry`는 `{requirement,teamReviewAvailable,canStartRequest,replyChannel,request}`다. 현재 DB 계정의 가입 cohort와 DOB, 서버의 Asia/Seoul 현재 연도 기준을 사용한다. DOB 원문·사용자 ID·보호자 연락처는 반환하지 않는다.

| 신호 | 화면 행동 |
| --- | --- |
| `requirement:NOT_REQUIRED` | LEGACY_EXEMPT 또는 유효 DOB의 KST 연도 차이 ≥15. 보호자 신청을 요구하지 않는다. 다른 이메일·역할·이용 조건이 충족됐다는 뜻은 아니다. |
| `requirement:BIRTHDATE_REQUIRED` | 신규 계정의 DOB가 없거나 유효하지 않다. 임의 날짜를 넣거나 보호자 승인으로 간주하지 않고 기존 DOB 확인·수정 요청 안내로 연결한다. 이 API는 DOB를 수정하지 않는다. |
| `requirement:REQUIRED` | 현재 서버 기준 보호자 절차 대상이다. 이미 승인된 계정도 대상 분류 자체는 유지하며 승인 여부는 `request.status`로 구분한다. |
| `teamReviewAvailable:false` | 비활성 또는 미확정 정책. `canStartRequest:false`, `replyChannel:null`, `request:null`이며 수집·신청을 열지 않는다. 이때 `request:null`은 과거 신청 부재를 증명하지 않는다. |
| 준비됨 + `canStartRequest:true` | 대상이며 새 신청이 가능하다. 새 계정은 `request:null`, 끝난 신청은 현재 `request`가 있을 수 있다. 사용자의 접수 동작 때만 새 키로 POST한다. |
| 준비됨 + 기존 `request` | 본인 기존 신청의 `View`다. 진행 중 또는 APPROVED이면 `canStartRequest:false`. DECLARED를 승인으로 표시하지 않는다. 읽는 순간 만료된 기존 신청은 기존 정리 규칙으로 EXPIRED 처리될 수 있으며 차수·기한을 연장하지 않는다. |

최초 화면의 회신 방법은 준비된 `/entry.replyChannel`, 접수 후·공개 보호자 화면은 `View.replyChannel`, 담당자 상세는 `Detail.replyChannel`을 사용한다. `EMAIL_REPLY`는 confirmation `method:EMAIL_REPLY`, `PHONE_CALLBACK`은 `method:PHONE`에 대응한다. 양식 문자열을 파싱해서 수단을 추측하거나 FE에서 운영 수단을 하드코딩하지 않는다. 기존 신청의 `currentNotice:false`이면 그 `View`·`Detail`의 `replyChannel:null`과 빈 양식을 따르고 계속 진행하지 않는다. Entry의 현재 정책 채널을 오래된 신청의 채널로 대입하지 않는다.

`canStartRequest`는 조회 시점의 안내 신호다. POST에서 현재 대상·정책·상태·idempotency·속도 제한을 다시 검사하므로 경합 이후 성공을 보장하지 않는다. 이 최초 진입 경로의 예외는 학습·파일·SSE·외부 AI 접근을 열지 않는다.

## 본인 접수·토큰·의사 표시 요청

접수의 최소 요청은 아래와 같다. 보호자 정보 없이 신청할 수 있으므로 아동에게 연락처를 먼저 요구하지 않는다.

```json
{
  "idempotencyKey": "intake-a-001",
  "guardianContactProvidedByChild": false
}
```

선택 입력은 `guardianName` 최대 100자, `guardianContact` 최대 254자다. 연락처는 이메일 또는 E.164 전화번호 한 개이며, 본인 접수에서 이 중 하나를 입력하면 `guardianContactProvidedByChild:true`를 함께 보낸다. 입력값·회신 본문·토큰을 로그, 분석 이벤트, URL query, 브라우저 영구 저장소에 넣지 않는다.

`Mutation`은 `{ "idempotencyKey": "link-a-001", "generation": 1, "revision": 1 }` 형태다. 실제 세대·수정번호는 직전 서버 응답을 사용한다. `Link`는 `{url, expiresAt, replayed, status}`이며 최초 URL의 토큰은 `/guardian-consent#token=...` fragment에 있다. FE는 토큰을 메모리에서 읽고 주소 표시줄에서 제거한 후 `/view` 또는 `/consent`의 JSON 본문으로만 보낸다. 서버·분석 도구·오류 보고에 토큰을 전송하지 않는다.

동일 발급 요청의 재시도는 `replayed:true`, `url:null`일 수 있다. 원문 토큰을 DB에서 복원하지 않으므로, 화면은 이를 새 링크라고 표시하지 않는다. 필요한 경우 상태를 새로 조회한 뒤 사용자의 명시적 재발급 동작으로 새 키를 사용한다. 재발급은 이전 토큰·확인 결과를 무효화하고 최초 수집일·신청 만료일을 연장하지 않는다.

다음은 합성 의사 표시 요청이다. 테스트 값은 실제 동의문 승인이나 아동 정보 수집 근거가 아니다.

```json
{
  "token": "<현재 메모리의 토큰>",
  "idempotencyKey": "consent-a-001",
  "generation": 1,
  "revision": 2,
  "noticeVersion": "<view의 동의문 버전>",
  "noticeDigest": "<view의 64자리 digest>",
  "accepted": true,
  "declaresLegalGuardian": true,
  "relationship": "PARENT",
  "scopes": ["SERVICE"]
}
```

`relationship`은 `PARENT`(부모) 또는 `MINOR_GUARDIAN`(미성년후견인)이다. 필수는 `SERVICE`이며, 설정된 선택 동의가 있고 직접 선택한 경우에만 `EXTERNAL_AI`를 포함한다. 보호자가 직접 제공하는 `guardianName`·`guardianContact`도 선택 필드다. `accepted:false` 거절 요청에는 이름·연락처를 넣지 않고 `scopes:[]`로 보낸다. 해당 요청은 `REJECTED / CONSENT_DECLINED`로 끝나며 새 개인정보를 받지 않는다.

의사 표시의 성공 응답 `DECLARED`는 이용 승인·법정대리인 관계 확인·명시적 이메일 회신 완료를 뜻하지 않는다. 담당자가 실제 회신·통화를 확인하고 수동 등록해야 `REVIEW_PENDING`으로 진행한다. 전화번호·OTP를 필수 필드로 표시하지 않는다.

의사 표시를 제출하면 해당 토큰을 소비한다. 응답을 잃은 같은 작업은 같은 키·같은 본문으로만 재시도하고, 소비된 토큰으로 `/view`를 반복 호출해 검토 상태를 조회하지 않는다. 필요한 회신 안내는 제출 전 화면의 메모리에만 유지한다. 신청 상태의 계속 조회는 로그인 본인의 `GET /api/users/me/guardian-requests`를 사용한다.

## 담당자 확인·결정 요청

`Confirmation`은 회신 원문 없이 구조화된 확인 결과를 보낸다.

```json
{
  "idempotencyKey": "confirm-a-001",
  "generation": 1,
  "revision": 3,
  "method": "EMAIL_REPLY",
  "evidenceReference": "mail-case-a-001",
  "responseReceivedAt": "2026-10-06T02:00:00Z",
  "noticeVersion": "<현재 동의문 버전>",
  "noticeDigest": "<현재 digest>",
  "relationship": "PARENT",
  "scopes": ["SERVICE"],
  "requestReferenceMatched": true,
  "responseExplicitlyConsents": true,
  "legalGuardianDeclarationConfirmed": true,
  "noticeAndScopesMatched": true,
  "confirmationMethodChecked": true
}
```

위 예시는 요청 필드를 보여 주기 위한 합성 예시다. 실제 체크는 담당자가 항목을 확인한 뒤 선택하며 초기값은 모두 `false`다. `method`는 현재 운영 설정과 정확히 일치해야 한다. `replyChannel=EMAIL_REPLY`이면 `EMAIL_REPLY`, `replyChannel=PHONE_CALLBACK`이면 `PHONE`만 허용하고, 이메일 설정에서 전화 예외를 자동 허용하지 않는다. FE는 현재 설정에 맞는 수단만 표시하며 불일치를 우회해 제출하지 않는다. `evidenceReference`는 `[A-Za-z0-9_.:-]` 1~100자의 제한된 참조이며 회신 본문·신분증·주민등록번호·전화번호·토큰·외부 원문 URL을 넣지 않는다. `responseReceivedAt`은 해당 신청 차수 시작 이후이며 현재 시각 이하인 실제 회신 수신 또는 전화 동의 시각이어야 한다. 뒤늦게 검토한 시각으로 과거 회신 시각을 바꾸지 않는다. 이 입력 검증이 발신자·관계 확인을 대신하지 않는다.

`requestReferenceMatched`는 번호뿐 아니라 회신의 `신청 차수`도 현재 `Status.generation`과 일치함을 담당자가 확인하는 체크다. 번호·버전이 같아도 차수가 다르거나 빠져 있으면 현재 회신으로 등록하지 않는다. 요청 본문의 `generation`·`revision`은 서버의 현재 값이고, 이 값 검증과 `responseReceivedAt >= generationStartedAt` 경계가 오래된 요청·회신을 제한한다. 일반 텍스트 검토 보조의 일치 표시만으로 이 체크를 자동 채우지 않는다.

웹 의사 표시가 먼저 있었다면 담당자가 등록하는 관계·범위도 해당 의사 표시와 맞아야 한다. 명시적 회신 내용을 바꾸어 등록하거나, 선택하지 않은 `EXTERNAL_AI`를 추가해 승인하지 않는다.

`Decision`은 아래 필드를 사용한다. 최종 승인 전에 별도 `confirmation` 등록이 필요하다.

```json
{
  "idempotencyKey": "decision-a-001",
  "generation": 1,
  "revision": 4,
  "decision": "APPROVE",
  "relationshipChecked": true,
  "guardianContactChecked": true,
  "evidenceReferenceChecked": true,
  "noticeAndScopesChecked": true
}
```

- `APPROVE`: `REVIEW_PENDING`과 명시적 확인 기록, 네 가지 담당자 체크가 필요하다. 실제 판단 없이 체크를 자동 채우지 않는다.
- `REJECT`: `reason`은 `RELATIONSHIP_UNCONFIRMED`, `CONSENT_DECLINED`, `INCOMPLETE_RESPONSE` 중 하나다.
- `NEEDS_INFORMATION`: `reason`은 `INCOMPLETE_RESPONSE` 또는 `RELATIONSHIP_UNCONFIRMED`다. 기존 확인 결과를 유지한 채 바로 승인하지 않으며 기한도 연장하지 않는다.

`Revoke`는 `idempotencyKey`, `generation`, `revision`, `reason`을 보내며 사유는 `OPERATOR_REVOKED`, `CONSENT_DECLINED`, `RELATIONSHIP_UNCONFIRMED`다. 보호자의 철회 연락은 정해진 확인 창구에서 담당자가 확인하고 이 경로로 처리한다. 본인도 `/withdraw`로 이용 승인을 해제할 수 있다.

본인의 `/withdraw`는 이메일 확인·연령/보호자 확인 대기 중에도 신청 취소를 할 수 있도록 해당 이용 게이트의 예외 경로다. 로그인·활성 계정·해당 신청 소유권·세대와 수정번호 검사는 유지한다. 확인 대기를 이유로 취소 버튼을 숨기거나 이용 승인이 있어야 취소할 수 있다고 안내하지 않는다.

## 상태·양식 응답

`Status`의 필드는 `requestId`, `generation`, `revision`, `state`, `noticeVersion`, `noticeDigest`, `requestExpiresAt`, `contactEraseDueAt`, `webDeclaredAt`, `explicitResponseAt`, `approvedUntil`, `currentNotice`, `serviceApproved`, `externalAiApproved`, `reason`이다. 시각은 ISO-8601 UTC 값이며 없는 시각은 `null`이다. FE 표시는 시간대가 분명하게 보이도록 한다. `requestExpiresAt`과 개인정보 정리 기한 `contactEraseDueAt`을 하나의 “링크 만료”로 합치지 않는다. 실제 링크 만료는 `Link.expiresAt`이다.

`View`는 `{status, noticeUrl, requiredScopes, optionalAiScope, replyChannel, forms}`다. `forms`에는 상태에 적용되는 문자열만 있다.

| 상태 | 공개·본인 `forms` 키 | 표시 의미 |
| --- | --- | --- |
| `AWAITING_CONSENT` | `receipt`, `consent_email`, `reply_form` | 안내·동의 확인 전 |
| `DECLARED` | `review_pending`, `reply_form` | 웹 의사 접수; 회신·전화 확인·승인 미완료 |
| `REVIEW_PENDING` | `review_pending`, `reply_form` | 담당자의 명시적 확인 등록 후 최종 검토 대기 |
| `NEEDS_INFORMATION` | `needs_information`, `consent_email`, `reply_form` | 보완 필요; 기존 확인 결과로 승인하지 않음 |
| `APPROVED` | `approved` | 서버가 유효한 승인 범위·기한을 판단 |
| `REJECTED` | `rejected` | 반려·동의 거절 |
| `REVOKED` | `revoked` | 신청 또는 승인 철회 |
| `EXPIRED` | `expired` | 신청·확인 또는 승인 기한 만료 |
| `WITHDRAWN` | `revoked` | 계정 탈퇴에 따른 종료 |

현재 안내 설정과 신청 설정이 다르면 `currentNotice:false`, `noticeUrl:null`, `requiredScopes:[]`, `optionalAiScope:null`, `forms:{}`다. 이전 동의문으로 진행 버튼을 열거나 FE에서 새 버전을 자동 대입하지 않는다. 현재 고지에서도 선택 AI 범위가 null/Java blank이면 `optionalAiScope:null`이며 선택지가 없다.

`Detail`은 `status`, 담당자 전용 `userId`, 기한 내 `guardianName`·`guardianContact`, `contactOrigin`, `generationStartedAt`, `declaredScopes`, `replyChannel`, `relationship`, `confirmationMethod`, `evidenceReference`, `events`, 전체 `forms`를 반환한다. `generationStartedAt`은 해당 차수 시작의 ISO-8601 UTC이며 재발급으로 이동하지 않는다. `declaredScopes`는 현재 차수의 구조화된 문자열 배열이며 아직 신고·수동 확인이 없거나 파기됐다면 `[]`다. `status.webDeclaredAt`이 있으면 웹 신고 범위이고, 웹 신고 없이 담당자가 확인을 등록했다면 그 확인 범위다. 웹 신고 이후 다른 범위로 confirmation을 등록하면 서버가 409를 반환한다. 따라서 배열만으로 웹 신고나 선택 동의의 출처를 추정하지 않고 시각·상태도 대조한다. 재발급·철회·새 차수는 이전 선언 범위를 되살리지 않는다.

`Detail.requiredScopes`와 `Detail.optionalAiScope`는 별도의 **현재 정책 선택지**다. 현재 고지와 신청의 전체 설정 digest가 일치하면 필수 범위는 `["SERVICE"]`, 설정된 선택 AI 범위는 `"EXTERNAL_AI"`다. 선택 AI가 없거나 blank이면 `null`이다. stale이면 두 값은 `[]`/`null`이며 `status.currentNotice:false`, `replyChannel:null`, `forms:{}`와 함께 쓰기를 닫는다. 기능 OFF·정책 미승인·설정 미준비에서는 상세를 반환하지 않고 기존 503 `GUARDIAN_TEAM_UNAVAILABLE`을 유지한다. fields/양식/과거 캐시에서 없는 범위를 추가하거나 DECLARED를 승인으로 해석하지 않는다.

선택지는 같은 응답의 `status.requestId`·`generation`·`revision`·`noticeVersion`·`noticeDigest`에 묶인다. revision은 신청 수정번호이고 숫자 정책 버전이 아니다. 고지 버전이 같아도 선택 AI·목적·보유 안내 등 전체 설정이 바뀌면 stale이다. 재조회·재발급·차수 변경·계정/권한 변경은 기존 선택지·입력/체크를 폐기하고 같은 최신 Detail로 다시 고정한다. 다른 Detail·Entry·View의 정책과 신청 번호를 합치지 않는다.

웹 선언이 있으면 실제 회신 등록 범위·관계가 그 선언과 정확히 일치해야 하며 현재 AI 선택지가 있어도 선언하지 않은 AI를 추가할 수 없다. 웹 선언이 없는 실제 명시적 회신만 현재 선택지 내에서 담당자가 수동 확인할 수 있다. 신청/승인 기한 만료나 종료 상태에서는 현재 정책 선택지가 보이더라도 쓰기·이용을 허용하지 않는다. 확인 전/DECLARED/REVIEW_PENDING에는 승인 flag가 false이며 최종 표시·이용은 `serviceApproved`와 `externalAiApproved`를 각각 따른다. 필수 SERVICE 확인·승인으로 선택 AI 승인을 추정하지 않는다. 이 추가 필드는 기능 활성화나 운영 정책 확정이 아니다.

`userId`, 세대 시작 시각과 선언 범위는 지정 관리자 상세에만 있으며 본인·공개 `View`나 `Status`에는 없다. 공개 화면에 복사하거나 분석 이벤트로 전송하지 않는다. 보호자 정보가 파기되면 해당 값은 `null`이며 FE가 이전 캐시에서 되살리지 않는다. `events`는 `{generation,revision,type,state,actorId,at}`의 최소 감사 이벤트다. 전체 양식 중 완료 상태 안내는 담당자의 미리 보기임을 표시하고 현재 신청의 상태와 구분한다.

`ListResponse`는 `{content,page,size,totalElements,totalPages}`이며 목록에는 `Status`만 포함한다. 신청번호·상태를 검색/분석 시스템에 추가로 전송하지 않는다. 화면을 벗어나거나 권한 오류가 발생하면 담당자 상세·연락처·증거 참조를 메모리에서도 비운다.

## 재전송·경합·권한 회수 처리

`idempotencyKey`는 `[A-Za-z0-9_.:-]` 1~64자다. 같은 작업 재시도에는 같은 키와 같은 본문을 보낸다. 같은 키로 본문을 바꾸거나 이전 `generation`·`revision`으로 새 작업을 제출하면 충돌한다. 요청을 전송한 직후 최신 응답을 반영하고, 승인·반려·철회를 FE에서 먼저 확정 표시하지 않는다.

| HTTP·코드 | 화면 처리 |
| --- | --- |
| 503 `GUARDIAN_TEAM_UNAVAILABLE` | 기능 준비 안내, 수집·전송 중단 |
| 404 `GUARDIAN_TEAM_REQUEST_NOT_FOUND` | 본인 접수 전에는 새 접수 안내; 타인 요청·사라진 상세는 표시 중단 |
| 400 `GUARDIAN_LINK_INVALID` | 유효하지 않거나 만료된 링크 안내; 토큰을 로그에 넣지 않음 |
| 409 `GUARDIAN_STATE_CONFLICT` | 상태를 새로 조회하고 담당자에게 변경 결과 표시; 승인·반려를 자동 재시도하지 않음 |
| 409 `GUARDIAN_NOTICE_CHANGED`, `GUARDIAN_TEAM_CONFIGURATION_CHANGED` | 새 안내·현재 신청 상태 확인, 과거 버전으로 계속하지 않음 |
| 409 `GUARDIAN_CONSENT_CHANGED` | 진행 중 작업·SSE 상태를 갱신하고 새 이용 조건 확인 |
| 403 `GUARDIAN_AI_CONSENT_REQUIRED` | 승인된 서비스 범위와 외부 AI 선택 동의를 구분해 안내 |
| 403 `AGE_VERIFICATION_REQUIRED`, `GUARDIAN_VERIFICATION_PENDING` | 기존 보호자 확인 제한 안내 유지 |
| 403 `ACCESS_DENIED`, 비활성 계정 오류 | 담당자 상세·입력값을 비우고 쓰기 중단 |
| 429 `RATE_LIMIT_EXCEEDED` | 반복 전송 중단, 즉시 반복 재시도 금지 |
| 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST` | 누락·형식 안내, 원문 응답·입력 로그 금지 |

승인 상태가 보이더라도 FE가 BE의 이메일 확인·필수동의·역할·소유권·멤버십 검사를 생략하지 않는다. 철회 후 재승인되어도 이전 세대의 AI 결과·SSE를 현재 승인으로 되살리지 않는다. 수신한 `forms`와 담당자가 확인하는 회신 내용은 모두 신뢰하지 않는 텍스트로 처리한다. HTML 렌더링, `innerHTML`, 스크립트 실행, 본문 자동 승인 규칙을 추가하지 않는다.

## 운영 연결 전에 확인할 것

한국어 [양식 안내](guardian-team-review-forms.md)의 실제 문구·동의 범위·확인 창구·담당자·기간 입력을 검토한다. 기본 BE 기능은 비활성이며 필수 입력이 비어 있으면 활성화가 차단된다. 초기 구현은 이메일 발송·수신 connector와 SMS를 제공하지 않으므로, 복사할 안내 문구를 실제 발송했다고 표시하지 않는다. 실제 메일함 운영과 확인·파기 절차, FE 배포와 BE 활성화는 별도 운영 승인 후 검증한다.

승인 증거 보유 설정은 최대 기한이며 조기 철회·반려·탈퇴 시 목적에 따른 증거 참조·로컬 사본 정리를 허용한다. 기술적으로 `approvedEvidenceRetention >= approvalValidity`가 필수지만 두 값의 실제 기간과 법적 최소·예외 보존은 아직 운영 검토가 필요하다. 테스트의 합성 기간을 화면의 실제 보유기간으로 사용하거나 “이 기간 동안 모든 증거가 보관된다”고 보장하지 않는다.
