# 출시 BE 후보의 배포 사전 점검과 복구 (#479)

**2026-10-06 현재 기준:** [PR532·V61–V63 TEAM 운영 준비](launch-team-operational-readiness.md)를 먼저 사용한다. 업체 없는 보호자 팀 확인이 현재 결정이며 아래 웹/SMS·V60 이전 기록은 당시 근거다. 현재 후보의 정리 hook·동의 epoch/digest와 TEAM enum reader를 보존해야 한다. 현재 source 준비는 실제 배포·권한·발송 승인이 아니다.

**2026-10-05 후속 기준:** [FE·메일·V60 운영 준비](launch-operational-readiness.md)는 BE521 `0a8f7bd`와 최신 FE `f1ad9d9`의 source/settings/artifact manifest, 승인된 KST 정책, SES 실제 검증·수신 1주소 범위와 V60 정리 hook 복구를 구체화한다. 아래 이전 후보/FE229/V53–V59 기록은 당시 근거로 보존한다. 현재 검토 대상은 V60을 포함하며 이전 Main 자동 rollback/실메일 활성화의 실행 근거로 이 문서를 사용하지 않는다.

브랜치 한 줄: `feature/479-deployment-preflight` — 신규 가입 제한과 기존 계정 이용을 보존하는 배포 순서·설정 점검·복구 조건을 정리한다.

이 문서는 후속 DEV 배포를 준비하는 검토 자료다. 서버 조회·설정 변경·배포·실메일 수신·운영 백업 복원을 실행했다는 기록이 아니다. develop push를 승인받더라도 `.github/workflows/deploy-dev.yml`이 두 서비스 이미지 빌드/push, DEV 컨테이너 교체, nginx 재시작과 Main 시작 시 Flyway를 실행하므로 해당 후보의 배포 영향까지 승인 범위에 들어가야 한다. main/prod 반영은 이 작업의 범위 밖이다.

## 1. 검토할 정확한 버전

2026-10-04 사전 점검 기준 upstream은 `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`이다. PR #500의 변경은 `ai-service/tests/` 아래 세 파일이며 서비스 런타임을 바꾸지 않는다. 통합 후보는 이 변경과 이미 merge된 #496/#497/#499를 보존한다. 최종 배포 검토에서는 최신 upstream, 실제 승인 대상 후보 SHA, 그 SHA의 필수 Main/AI CI, 실행 중인 Main/AI image tag와 RepoDigest, 실제 FE build SHA를 다시 기록한다. 소스 브랜치 SHA를 서버 배포 증거로 대신 쓰지 않는다.

최초 FE develop `1b6987d`의 DOB 입력·verify route/API·이메일 필드 누락은 이전 source/mock baseline이다. [PR507 최신 소비자 검증](qa/fe-auth-contract/FE-PR229-REVIEW.md)은 준비된 ON의 기존5개 해소와 OFF 제약을 별도로 확인한다. 이후 FE229 merge `5e91b5789daa13b3a4923653285709d18f232c79`에서 이 지원을 source로 확인했고 해당 FE deploy workflow의 success metadata를 확인했다. 기본 OFF이므로 실제 신규 가입·메일 경로 인수로 계산하지 않는다. FE의 직접 `/verify-email` GET404 보고와 기존 BE Nginx 경로 누락, access log query/Referer 및 별도 error-log 경계는 [후속 검토](https://github.com/AutoAI-UTEUM/BE/blob/27c639e80838f203a721783db2dbffd164e73987/docs/qa/fe-auth-contract/NGINX-FOLLOWUP.md)에서 구분한다. 실제 BE/FE artifact·유효 메일 origin·로그 보호·합성 인수/활성화 합의가 필요하다. [가입·이메일 계약](email-verification.md), [DOB 기반](birthdate-guardian-foundation.md), [기존 계정 예외](legacy-account-access.md)를 대조하며 BE gate나 신규 cohort를 바꾸어 우회하지 않는다.

## 2. 배포 전 조건

| 확인 대상 | 필요한 증거와 중단 조건 |
| --- | --- |
| 코드·CI | 승인 대상 SHA가 검토한 기능 브랜치를 포함하고 필수 Main/AI CI가 같은 head에서 성공해야 한다. 실패·진행 중·다른 SHA의 CI는 성공으로 대체하지 않는다. |
| FE | 신규 LOCAL/Google DOB·현재 정책 동의, 이메일 확인 링크 화면·명시적 POST·상태 재조회·재발급, legacy 표시와 신규 403 안내가 지원되는 build를 확인한다. FE 미준비 상태에서는 후속 BE 배포를 보류한다. |
| 메일 | provider/from/base URL/지역과 수신 경로를 담당자가 확인한다. `enabled=false` 또는 logging 수락은 실메일 수신 증거가 아니다. 실제 수신 시험은 승인된 합성 주소와 비용·권한 범위에서 별도로 수행한다. 확인 경로가 없으면 신규 사용자는 PENDING으로 계속 차단된다. |
| DB | 현재 Flyway 성공/실패 버전과 V53–V59 적용 순서·checksum을 확인한다. 미리 적용된 버전이 있으면 checksum과 실제 schema를 대조한다. 실패 이력 삭제, repair, out-of-order 또는 VERIFIED/DOB/guardian 성공 백필로 진행하지 않는다. |
| 백업 | 새 migration 직전 승인된 DB 백업의 시각·크기·압축 무결성·hash와 접근 제한을 확인한다. DB 덤프에는 원본 PDF/렌더가 없으므로 파일 보관·복구 증거를 별도로 준비한다. 예전 백업의 존재나 로컬 합성 복원 검사를 실제 운영 복구 완료로 표시하지 않는다. |
| 기존 계정 | V58 직전에 존재한 계정의 cohort가 migration 후 LEGACY_EXEMPT이고 기존 DOB/이메일/guardian 증거가 보존되는지 검증할 합성 사례를 준비한다. 정지·탈퇴·역할·소유권 제한은 예외에 포함되지 않는다. |
| 신규 계정 | LOCAL/새 Google 가입이 NEW_SIGNUP/PENDING으로 시작하고 이메일 확인 전 업무 API·파일·SSE·외부 AI가 거부되는지 확인한다. Google 기존 subject 로그인은 DOB 재입력을 요구하지 않는다. |
| 복구 이미지 | 아래 5절의 조건을 만족하는 불변 SHA 이미지와 설정/Compose 사본을 준비한다. 신규 계정 gate가 없는 옛 이미지로의 복귀를 일반 롤백으로 승인하지 않는다. |
| 운영 권한 | 실제 서버 접근·메일 시험·백업/복원·컨테이너 교체에 필요한 접근 및 해당 후보 배포 승인을 확인한다. 이 문서 작성이나 로컬 테스트가 외부 작업 승인을 넓히지 않는다. |

당시 보호자 정상 경로는 웹 동의 → SMS 확인, 실패·불일치·분쟁만 수동 접수였으나 2026-10-06 업체 없는 TEAM_REVIEW로 결정이 갱신됐다. 이 이전 foundation 후보의 PHONE_CONFIRMED는 전화 통제 증거이며 연령/보호자 이용 승인 상태가 아니다. 현재 관계 기준·고지·담당 권한·기간·FE 연결은 위 TEAM 준비 자료에서 확인하며 foundation 배포와 만14세미만 포함 출시 완료를 구분한다.

## 3. 실제 설정 대조

설정은 승인된 운영자가 실제 컨테이너의 **유효 값**과 대조한다. `.env` 원문, `docker inspect` 전체 Env, `docker compose config` 전체 출력, JWT/DB/내부 토큰/암호화 키/개인 메일 주소는 이슈·로그·증거에 올리지 않는다. 증거에는 활성 여부·provider 종류·base URL의 승인된 origin·키의 유지 여부와 공개 가능한 digest/count만 기록한다.

| 설정/경계 | 현재 소스 동작과 점검 |
| --- | --- |
| `EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED` | application/Compose 기본 false지만 실제 운영값은 별도 확인한다. 이미 켜진 필수 동의 설정을 끄지 않는다. 현재 `requiresConsent=true`인 정책 버전과 FE의 실제 선택·기록이 맞아야 한다. |
| `EDUPILOT_MAIL_ENABLED`, `EDUPILOT_MAIL_PROVIDER` | 발송 기본 false. base Compose의 provider 기본 logging에 production override 파일이 ses 기본을 덮는다. DEV도 그 override 파일을 사용하므로 YAML 한 파일만 보고 provider를 단정하지 않는다. |
| `EDUPILOT_MAIL_BASE_URL` | base Compose 기본은 dev origin, production override 기본은 www origin이다. DEV 배포의 실제 값이 승인된 FE 확인 화면 origin인지 확인한다. 다른 환경의 링크가 발송되면 중단한다. |
| `EDUPILOT_MAIL_FROM`, `AWS_REGION` | 승인된 sender/domain/지역 및 provider 사용 가능 여부를 확인한다. 이 변경은 SES 권한·도메인·비용 설정을 만들지 않는다. |
| `EDUPILOT_MAIL_OUTBOX_ENCRYPTION_KEY` | 선택 설정이다. 지정하면 Base64 decode 32바이트여야 한다. 미지정 시 기존 JWT secret에서 HKDF-SHA256 메일 전용 키를 유도한다. 새 키를 기동 필수로 오인하지 않는다. 재시작·복구 이미지·여러 서버는 같은 유효 키를 유지한다. 키/JWT secret 변경 시 이전 미완료 payload 복호화가 실패할 수 있으므로 임의 생성·교체하지 않는다. |
| 이메일 gate·cohort | 이메일 gate를 끄는 배포 flag는 없다. V58의 LEGACY_EXEMPT는 기존 계정 이용 정책이며 확인 성공 증거가 아니다. 새 User와 migration 이후 SQL 기본값은 NEW_SIGNUP이다. |
| 일반 파일 30일 | `edupilot.deletion.general-files.retention-days=30`은 직접 일반 자료 삭제의 원본/렌더 기한만 기록한다. 30 외 값은 기동 실패다. 기한 기록만으로 물리 worker를 활성화하지 않는다. |
| 물리 삭제·guardian 웹/SMS | `edupilot.deletion.enabled=false`, guardian web 기본 false와 미연결 SMS provider를 유지한다. 현재 Compose는 이 두 기능의 활성화 환경변수를 Main에 전달하지 않는다. `.env`에 이름만 추가해 활성화됐다고 판단하지 않는다. 런타임 설정을 별도로 주입·수정하는 작업은 승인된 후속 범위에서 검토한다. |
| Google·CORS·AI | 기존 Google client ID, 허용 FE origin과 PUT, Main→AI 토큰/timeout 및 이미 승인된 AI 설정을 보존한다. 키 출력·권한 확장·유료 AI 호출로 사전 점검을 대신하지 않는다. |

## 4. 적용 순서와 관측

### FE 기본 OFF와 현재 공개 계약 신호

현재 인증 capability endpoint나 가입 계약의 활성 여부를 반환하는 공개 배포 flag는 없다. `/api/health`의 `data.status=UP`과 `/api/health/ready`의 DB/AI readiness는 가입·메일 준비 증거가 아니다. 공개 `/v3/api-docs`에는 SignupRequest의 필수 DOB와 이메일 확인 경로가 있지만 이는 schema 존재 증거이며 실제 수신·FE 준비·출시 승인 신호로 사용하지 않는다. 내부 AI `capabilities`는 인증 계약의 신호와 별개다.

최소 지원 방안은 별도 API 증설 없이 FE가 기본 OFF인 배포 설정으로 검토된 BE 계약/FE build 조합을 명시하는 것이다. 실제 적용 책임자는 승인된 SHA와 활성 시점을 함께 관리한다. OFF일 때 기존 화면/서버 계약을 보존하고 새 화면을 숨기는 것은 FE 단계의 호환 방안이다. **새 BE의 DOB·이메일 gate는 조건부로 꺼지지 않으므로 FE OFF 상태로 후보 BE만 배포하는 것은 허용 경로가 아니다.** 기존 계정 로그인과 신규 가입의 전환을 분리하고, 새 가입 요청을 오류 응답으로 시험하면서 자동 활성화하지 않는다.

| 배포 조합 | 의미 |
| --- | --- |
| 이전 BE + FE 새 흐름 OFF | 기존 계약을 유지하는 선반영 후보. 해당 FE build에서 기존 동선을 검증해야 한다. |
| 후보 BE + FE 새 흐름 OFF | 신규 DOB/이메일 UI가 없어 400/409/403이 남는다. 후속 BE 배포 조건을 충족하지 않는다. |
| 검토한 후보 BE + 대응 FE 흐름 ON | 승인된 동시 전환과 실제 메일 경로·합성 인수 조건이 필요하다. 연령/보호자 출시 완료를 뜻하지 않는다. |

FE가 서버별 자동 계약 선택을 꼭 요구하면, 현재 입력 필수 여부와 **구현 지원**을 알리는 좁은 읽기 전용 신호의 필요성을 계약 담당과 먼저 확인한다. 이 문서에서는 endpoint/응답 필드·새 서버 flag를 만들지 않는다. 신호를 추가하더라도 실제 NEW_SIGNUP DOB/이메일 요구를 정확히 표현해야 하고, DB gate의 대체 조건이나 실메일 수신/guardian 승인으로 소비하면 안 된다. 네트워크 오류·404·알 수 없는 revision에서는 임의 새 기능 활성화 없이 기존 안전한 동선을 유지하며 후보 BE 전환은 보류한다.

### 새 capability API 없는 수동 활성화안

다음은 **FE/운영자가 합의해야 할 미실행 절차**다. 자동 서버 선택 대신 승인된 배포 manifest와 FE 빌드 설정으로 계약을 고정할 수 있다는 최소안이며, 새 API 구현을 필수로 만들지 않는다. FE229의 실제 선택값은 `VITE_AUTH_CONTRACT_READINESS=be-auth-ee69e425-v1`이고 기본 OFF다. 승인한 후보 SHA/이미지/FE build와 이 계약값의 적용을 함께 확인한다. `VITE_API_CAPABILITIES`도 FE build 설정이며 두 값 모두 BE 신호가 아니다.

1. 최종 BE 후보 SHA·Main/AI 이미지 digest·V53–V59 결과·대응 FE build SHA·승인한 API/메일 origin·합의한 FE flag 상태를 하나의 검토 manifest에 고정한다. 담당자가 실제 배포 artifact를 read-back한다. status 응답이나 frontend에 수동으로 넣은 SHA를 artifact 증거로 대신하지 않는다.
2. 승인된 전환 창에서 가입 관련 쓰기와 migration 중 User INSERT를 통제한다. 기존 FE 기본 OFF로 이전 BE를 유지하는 선반영과, 대응 FE 흐름 ON + 후보 BE로의 전환을 구분한다. 순차 컨테이너 교체 중에 신규 가입을 받지 않고 준비된 조합이 확인되기 전 공개 가입을 재개하지 않는다. 실제 트래픽 통제 방식/권한은 운영자가 확정해야 하며 새 서버 flag를 가정하지 않는다.
3. 같은 창에서 승인된 BE/FE 조합을 적용하고 유효 메일 base가 FE `/verify-email` origin인지 확인한다. 동의 대상은 현재 policies에서 얻고 required 설정을 보존한다. 실메일 담당·합성 수신 시험의 승인과 수신 근거가 없으면 전환 조건을 충족하지 않는다.
4. 별도 승인된 전용 합성 계정으로 인증된 `GET /api/auth/email-verification/status`와 `GET /api/users/me`의 세 이메일 필드/타입을 대조한다. 신규 PENDING은 required=true·verifiedAt=null 및 업무403, V58 기존 예외는 UNKNOWN/PENDING·required=false·증거불변과 기존 이용을 확인한다. false만으로 cohort를 추정하지 않고 전환 전후 합성 사례로 구분한다. 401/정지/권한/404/형식 오류를 지원 성공으로 처리하지 않는다.
5. DOB/동의 전달, 202 접수, GET 비소비·명시적 POST 1회 확정·재발급/만료·다른 로그인 계정 상태 재조회, 실제 메일 경로와 FE 브라우저 동선을 인수한다. 예측한 응답이나 로컬 source/mock 결과를 이 실검증으로 대신하지 않는다. 검토 manifest와 인수가 일치한 뒤에만 해당 FE 흐름 활성 상태로 가입 쓰기를 재개한다. 실패하면 트래픽 통제를 유지하고 5절의 gate 보존 복구 조건을 따른다. FE flag OFF만으로 복구가 완료됐다고 판단하지 않는다.

이 방식은 수동 릴리스 조율에 동의하고 통제된 전환/합성 인수가 가능한 경우의 제안이다. FE가 자동 revision 협상을 요구하면 별도 계약 합의가 필요하다. 어떤 방식도 메일 성공을 연령/보호자 이용 승인으로 바꾸거나 미완료 age/AI 동의 경계를 대신하지 않는다.

가입 동의는 현재 `GET /api/policies/current`의 `requiresConsent=true` 대상과 실제 `EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED`를 따로 대조한다. 대상이 0개일 때의 현재 코드 동작과 UNKNOWN/LEGACY 응답은 [정합화한 API 명세](https://github.com/AutoAI-UTEUM/BE/blob/dbd148352753e2b52f8f9cbf8599c5024c61dd8d/docs/api-spec.md) 및 [중간 후보의 전달 계약](https://github.com/AutoAI-UTEUM/BE/blob/dbd148352753e2b52f8f9cbf8599c5024c61dd8d/docs/qa/fe-auth-contract/README.md)을 사용한다. 두 링크는 추가 FE 회신 이전 검토 시점에 고정돼 있으며 최종 통합의 최신 계약은 별도로 확인한다. 법무 준비 상태·기존 계정 예외·현재 동의 완료를 capability나 health로 추정하지 않는다.

1. 실제 FE 지원 build와 실메일 확인 경로, 승인 대상 SHA·설정·CI·백업·복구 이미지 증거를 갖춘다. 기존 FE가 새 필드를 아직 보내지 않는 상태에서 BE만 먼저 적용하지 않는다. FE 선반영 가능 여부와 활성화 시점은 해당 팀이 검증해야 한다.
2. 승인된 migration 창에서 쓰기 유입과 기존 프로세스의 종료 경계를 통제한다. V58 cohort 경계를 기록할 때 migration 중 가입/기타 User INSERT가 섞이지 않도록 한다. 연속된 migration의 checksum과 적용 결과를 기록한다.
3. V53 신규 노트 멱등성 컬럼/고유키 → V54 암호화 outbox/메일 예약 → V55 이메일 증거/토큰 → V56 삭제 원장 → V57 DOB/접수 → V58 기존 cohort → V59 웹/SMS 접수 순서를 따른다. V53은 수정된 중복을 포함한 기존 노트를 보존하고 신규 쓰기에 멱등성을 적용한다. V54 과거 메일 시도 예산/QUEUED 실패 표시는 기존 데이터 영향을 가진다. 빈 DB에서의 성공만으로 실제 데이터 migration 안전을 판단하지 않는다.
4. 승인한 후보 이미지로 Main/AI를 실행하고 정확한 두 tag/RepoDigest, Flyway 성공/실패와 health/readiness를 확인한다. `UP`만으로 가입·메일·기존 계정 예외가 검증됐다고 표시하지 않는다.
5. 승인된 전용 합성 계정으로 기존 예외 이용/정지·타인 거부, 신규 DOB/동의 오류, 이메일 PENDING 403, 링크 GET 비소비·POST 1회 확정·재발급·상태 재조회, refresh 후 동일 동작을 확인한다. 실제 수신과 API 확정은 별도 결과로 기록한다. 외부 AI 전송 검증에는 mock/허가된 환경을 사용한다.
6. 메일 READY/CLAIMED/SENDING/UNKNOWN/FAILED의 상태별 집계와 worker 회수를 살핀다. UNKNOWN은 성공이나 무조건 재발송 대상이 아니다. 삭제 원장은 POLICY_PENDING/일반 30일 기한을 확인하고 실제 물리 삭제·SMS 확인 성공·연령 승인 검증은 이 배포에 포함하지 않는다.

실서버에서 사용할 승인된 조회 경로로 다음 **집계만** 대조할 수 있다. 아래 SQL은 문서 작성 중 실행하지 않았다. migration 성공 후 해당 schema에서 운영자가 실행하며 ID·이메일·파일키·본문·token은 조회하지 않는다.

```sql
SELECT version, description, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT access_cohort, status, email_verification_state, COUNT(*) AS users_count
FROM users GROUP BY access_cohort, status, email_verification_state;
SELECT COUNT(*) AS invalid_verified_evidence
FROM users
WHERE (email_verification_state = 'VERIFIED' AND email_verified_at IS NULL)
   OR (email_verification_state <> 'VERIFIED' AND email_verified_at IS NOT NULL);
SELECT status, COUNT(*) AS outbox_count FROM email_outbox GROUP BY status;
SELECT status, COUNT(*) AS intent_count FROM deletion_intents GROUP BY status;
```

cohort 집계만으로 migration 전 존재한 개별 계정과 migration 후 생성한 계정의 구분까지 증명할 수 없다. 그 조건은 전용 합성 사례와 migration 전후 자료로 확인한다. 증거 오류·실패·FE 불호환·확인 링크 미수신·신규 제한 누락 시 진행을 멈추고 원인을 해결한다. 제한을 끄거나 신규 계정을 LEGACY_EXEMPT/VERIFIED로 변경하지 않는다.

## 5. 복구와 롤백 조건

- 코드 rollback과 DB restore는 다른 작업이다. V53–V59의 schema/감사/확인/cohort/삭제 원장을 제거하거나 down migration/repair로 되돌리는 절차는 제공하지 않는다. old 코드가 추가 컬럼을 무시하고 기동한다는 사실만으로 안전한 rollback을 판단하지 않는다.
- 복구 이미지는 현재 DB의 cohort와 확인 증거를 읽고 **NEW_SIGNUP 제한·LEGACY_EXEMPT 이용·정지/탈퇴·소유권/역할**을 유지해야 한다. DOB 필수 계약과 FE도 함께 맞아야 한다. 이전 develop `0d0234e...`/`ef8f0f7...` 런타임에는 이 후보의 신규 gate가 없으므로 자동 복귀 대상이 아니다. 검증된 복구 이미지가 없으면 담당자는 승인된 유지보수/트래픽 통제 상태에서 수정 이미지가 준비될 때까지 기다린다. 장애를 이유로 gate를 해제하지 않는다.
- 메일 발송 긴급 중지는 기존 `EDUPILOT_MAIL_ENABLED=false` 경계지만 이미 SENDING인 호출을 취소하거나 신규 사용자의 확인을 완료하지 않는다. 구 worker가 V54 outbox를 회수하지 않는 문제와 미완료/UNKNOWN 작업을 별도로 기록한다. 복구 과정에서도 유효 암호화 키를 보존한다.
- 물리 삭제 비활성·미정 목적 기한을 유지한다. 일반 30일은 확정된 범위이며 복구나 더 짧은 실행 정책으로 이미 기록한 `retainUntil`을 단축하지 않는다. 논리 삭제된 자료에 접근을 다시 열어 복구됐다고 표시하지 않는다.
- 실제 DB restore는 [운영 복구 런북](runbook-db-restore.md)의 별도 승인 작업이다. 오래된 백업과 독립 보관한 최신 [삭제 원장](deletion-journal.md)의 무결성/epoch를 검증하고 재적용 후 트래픽을 연다. 이메일/guardian/cohort 상태를 백업보다 최신이라고 추정하지 않는다. 원장 영구 반출 도구·파일 복구·실운영 리허설은 아직 완료 증거가 없다.

## 6. 결과 기록

이슈 #479/통합 PR에는 승인 범위, upstream/후보/FE/실행 중 이미지 SHA·digest, 같은 SHA CI, 로컬 검사와 실제 운영 검사를 구분한 결과, migration·백업 무결성, 키 유지 여부, 합성 역할별 접근 결과, 미완료/실패/미실행 및 복구 담당자를 남긴다. 비밀·실학생·실메일 본문은 포함하지 않는다. 실DEV NOTE04 fixture는 별도 계획·승인 작업이며 로컬 103개 dry-run이 실제 DEV 쓰기 승인이 되지 않는다.

## 7. AI 팀 보고와 Spring 계약의 연결

2026-10-04 10:05:04 UTC의 AI 팀 전달 메시지는 #496/#497/#499/#500의 develop/DEV 반영, `ef8f0f7`의 Linux CI 1053개, 추가 무료 합성 110개(PDF 12/report 98) 통과·paid model 0회를 보고했다. 이 후보에 #500의 merge SHA `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`와 그 이전 변경이 포함된 것은 Git 이력으로 확인했다. **110개 실행·실행 중 이미지·health/readiness·로그 운영 상태는 AI 팀 보고이며 이 작업의 독립 실측이 아니다.** 두 서비스의 개별 CI나 무료 합성 수량을 Spring↔AI↔FE 전체 연동·부하·실모델 품질 인수로 합산하지 않는다.

Spring 쪽 독립 증거는 #499 이후 후보 `b6f4f5f3155f8e1799698bcdea956fbd64f22d61`에서 실행한 아래 두 suite의 **57/57, 실패/오류/skip 0**이다. 이후 #500과 FE/fixture/preflight 변경은 Main tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`를 바꾸지 않는다. 이전 실행을 최종 head의 로컬 재실행이라고 쓰지 않고, 최종 후보 head의 CI와 별도로 기록한다.

| 경계 | 기존 Spring 증거 | 입증 범위와 남은 연결 |
| --- | --- | --- |
| 요청·오류·재시도 | [HttpAiClientContractTest](../main-service/src/test/java/io/edupilot/ai/HttpAiClientContractTest.java) 50개: internal/trace header, 요청 DTO, schema/auth 오류, remote retryable INTERNAL/TIMEOUT의 1회 재시도, transport timeout 무재시도, 안전한 구조 로그 | MockWebServer 응답과 Main client의 계약이다. 실제 AI 라우트·배포 토큰·provider 호출을 함께 실행한 증거가 아니다. |
| PDF·외부 파일 | 같은 suite의 multipart/extract 페이지·upload ID 검증, delete 204 성공·route 404/provider 실패를 완료로 처리하지 않는 검사 | AI 팀 PDF 12개와 대조할 route/schema/hash/오류별 합성 case manifest가 필요하다. provider 삭제 완료·원본/렌더 운영 복구로 확대하지 않는다. |
| report source·중복·권한 | [ReportSnapshotBuilderJpaTest](../main-service/src/test/java/io/edupilot/report/ReportSnapshotBuilderJpaTest.java) 7개: 7개 source 수집·대표 제출·중복 방지, 최신 graded 선택·실패 제외, 다른 학생/강의실·탈퇴 수강생 차단. HTTP suite는 report JSON/usage/전용 timeout 검사 | Main snapshot/H2와 mock HTTP의 증거다. AI 팀 report 98개의 sourceType·duplicate 판정과 같은 synthetic 입력/hash로 연결해야 한다. 실제 두 서비스 왕복·동시 report 생성 인수는 별도다. |
| 시험 초안·timeout | HTTP suite의 `/internal/ai/exams/draft`와 questionType 계약, per-call turn/extract/report timeout. [ExamDraftApiContractTest](../main-service/src/test/java/io/edupilot/exam/ExamDraftApiContractTest.java)는 기존 draft/권한/응답 검증 경로다 | 단일 read timeout이나 AI 자체 timeout만으로 queue·내부 재시도·Spring HTTP·클라이언트까지 포함한 총예산을 입증하지 않는다. 기존 API를 다시 만들지 않는다. |

추가로 유용한 비용 없는 검증은 (1) AI 팀의 비밀 없는 110-case manifest와 exact source/CI artifact를 위 case에 매핑하고, (2) 독립 합성 환경에서 실제 Main→로컬 AI 양쪽 라우트를 연결해 schema/오류/중복 판정을 확인하며, (3) 가짜 provider·제어된 지연으로 총 deadline·취소·포화/재시작의 종단 결과를 관찰하는 것이다. 현재 이 추가 연결 실행은 **NOT_RUN**이고 새 결함을 확정한 결과도 아니다. 실DEV 구성/이미지/로그 접근 조회는 승인된 운영 경로에서 별도 확인한다.

#500의 [monetary reservation 설명](../ai-service/tests/benchmarks/MONETARY-RESERVATIONS.md)은 offline 평가 원장이지 기관/강의별 서비스 quota 구현이 아니다. reasoning을 포함한 신뢰할 비용 상한이 없다는 AI 팀 보류와 일치하며, paid 호출을 실행하지 않는다. 월 기관 예산은 미정이다. 회신의 로그 14일 보존·한승준 운영 담당은 타팀 보고/제안이고 이 문서가 사용자 승인이나 실제 접근 권한 설정을 확정하지 않는다. 로그 정책·권한·보존 설정은 변경하지 않았다.

[FE230](https://github.com/AutoAI-UTEUM/FE/pull/230)의 report 소비자는 실패 코드 한국어 안내·기존 완료 report 보존·FAILED 점수 미표시·새 requestId 한 번 retry를 다룬다. 조회 시 원격은 source `49bc84a0abe608bc7434de95fc10b5b024ca0896`, merge `5843c8f6f85152126c5d4b4263916310ff3b95b6`였다. 실제 배포/실계정 인수는 이 작업에서 확인하지 않았다. AI의 422/502가 FE에 직접 전달되는 계약이 아니라, [ReportGenerationWorker](../main-service/src/main/java/io/edupilot/report/ReportGenerationWorker.java)가 실패를 저장하고 [공개 controller](../main-service/src/main/java/io/edupilot/report/ReportController.java)의 상세 GET이 HTTP200 `FAILED/failureCode/fallback`을 반환하는 경계다. [ReportFailedResponse](../main-service/src/main/java/io/edupilot/report/dto/ReportFailedResponse.java)의 fallback은 metrics/dataQuality이며 완료 점수가 아니다. 기존 [ReportApiContractTest](../main-service/src/test/java/io/edupilot/report/ReportApiContractTest.java)에 FAILED/timeout/fallback·criteria 부재와 null 부족 증거/실제0점 구분이 있다. 이 소스와 기존 exact4e Main CI를 연결하며 FE230 작성자 보고 테스트 숫자를 두 서비스 실연동 인수로 합산하지 않는다. FE 코드는 수정하지 않았다.
