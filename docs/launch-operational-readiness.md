# FE·메일·V60 운영 준비 (#479, #515, #519)

브랜치 한 줄: `feature/479-launch-ops-readiness` — FE ON manifest, SES 최소 조회·발송 인수와 V60 정리 hook을 보존하는 전환·복구 조건을 준비한다.

2026-10-05의 배포 독립 준비다. 기준은 [BE521](https://github.com/AutoAI-UTEUM/BE/pull/521) `0a8f7bd99db13d20ddcf392766b2bd69ab4754a3`, FE 읽기 기준은 `f1ad9d924b4768f798dc438066a311a2aac955f0`이다. 실제 FE 변경, 서버 조회·설정 변경·배포·계정 변경·메일 발송·DB 복원은 이 문서 작성으로 실행되거나 승인되지 않는다. 기존 단기 가입/Google pause·합성 계정 2개/세션 1개 승인과 실제 메일/SMS/유료 AI 미승인 경계를 유지한다. 담당자·실행 창·대기 시간은 임의로 확정하지 않는다.

## 1. FE에 전달할 확정 사항과 준비 누락

| 항목 | 확인된 계약 / 필요한 작업 |
| --- | --- |
| 승인 정책 | Asia/Seoul 오늘 이후 DOB는 400 `VALIDATION_FAILED`. KST 현재 연도−출생 연도 ≤14는 보호자 대상, ≥15는 보호자 불필요. 생일/윤년 보정 없음. 가입 후 본인 DOB 직접 변경 없음. |
| 이용 제한 | NEW_SIGNUP은 이메일 확인이 먼저다. 이어 `AGE_VERIFICATION_REQUIRED` / `GUARDIAN_VERIFICATION_PENDING` 403을 처리한다. 이메일/guardian 성공을 생성하지 않고 기존 LEGACY_EXEMPT·역할·소유권·필수동의를 유지한다. DOB가 User 응답에 추가되지 않는다. |
| 수정 요청 | 본인 `POST/GET /api/users/me/birthdate-correction-requests`, body `{requestedDateOfBirth}`. 동일 날짜 재요청은 멱등, 다른 대기 날짜는 409 `BIRTHDATE_CORRECTION_PENDING`. 접수 상태를 DOB 수정·보호자 승인 완료로 표시할 수 없다. 관리자 목록/상세는 GET만 존재한다. |
| 링크 | 신규 발급은 `/verify-email#token=...`; 자동 confirm 없음, 명시 POST. FE232는 query 링크를 거부하고 재발급을 안내한다. BE521은 직접 SPA 경로를 포함하지만 현재 DEV에 반영됐다는 뜻은 아니다. |
| 현재 FE source | `launchAuthContract.ts`의 정확한 ON 값은 `be-auth-ee69e425-v1`. 기존 FE dev workflow에는 이 값 주입이 없다. 다른 값/누락은 OFF다. 이 문자열은 옛 계약 이름이며 BE521 배포·KST 계약 전체 지원을 증명하지 않는다. |
| 현재 FE 누락 | 날짜 검사는 달력 형식만 확인하며 KST 미래일 안내/guardian 403 전용 처리·수정 요청 UI가 없다. `isAccountManagementRequest`에 수정 요청 경로가 없어 이메일 대기 계정의 본인 요청도 FE에서 막힐 수 있다. guardian 차단에서도 본인 관리 경로와 세션을 유지해야 한다. |

FE 담당자에게 제안하는 최소 작업은 승인된 KST 안내·오류 처리, 두 guardian 403의 업무 호출 중단/세션 유지, 본인 수정 요청 접수·조회와 관리 경로 허용, fragment 재발급 흐름 검증이다. 날짜 입력만으로 guardian 완료를 표시하지 않는다. 새 readiness 문자열로 바꿀지 기존 값을 별도 고정 manifest와 함께 쓸지는 FE/부모 결정이다. BE에서 FE 설정을 대신 켜거나 capability endpoint를 새로 만들지 않는다.

### source / settings / artifact manifest

[예제 manifest](qa/launch-ops/readiness-manifest.example.json)는 알려진 source/CI와 **미확인 값 null**을 함께 보존한다. 실제 값과 증거 링크는 운영자가 별도 비공개 사본에 기록한다.

- BE: 최종 승인 commit, BE521 포함 여부, 동일 head Main/AI CI, Main/AI image의 SHA tag와 실제 RepoDigest, Nginx/Compose 원본과 대상 hash, Flyway 실제 적용 버전·checksum·성공 여부. 예제의 SHA256은 Git blob 바이트 기준이며 Flyway의 정수 checksum과 다르다.
- FE: 최종 준비 source commit·동일 head CI/build run, `/api`와 기존 capability 목록, 정확한 readiness 주입 값, 기존 Google client 설정의 비밀값 없는 참조. `dist/index.html`·entry JS·각 CSS의 경로와 SHA256, 최종 전체 파일 manifest hash, 배포 후 root/verify-email/asset hash의 일치. 공개 HTML/HTTP200만으로 ON 여부를 판단하지 않는다.
- 설정은 **Vite build 입력**이다. Nginx/컨테이너 환경만 바꾸거나 옛 OFF dist를 재복사하면 ON이 되지 않는다. 기존 FE workflow는 동시 실행을 막지만 BE workflow와 공동 잠금을 공유하지 않는다. 두 담당자는 source 변경/동시 배포를 동결하고 동일 창의 artifact를 고정해야 한다.
- BE source CI는 target Docker image를 만들지 않는다. 기존 DEV workflow는 develop push 후 image build/push→설정 복사→재생성/Flyway를 연속 실행한다. 아직 target image digest가 없으며 prebuild/staging 경로 또는 이 연속 경로의 전환 계획이 결정돼야 한다. `latest`를 복구 근거로 사용하지 않는다.

로컬 검사는 `node scripts/qa/launch-ops/check-manifest.mjs MANIFEST prepare` 또는 `... MANIFEST reopen`이다. 파일의 항목 누락·SHA 불일치를 찾는 검토 보조이며 실제 서버나 승인 사실을 검증하지 않는다. 예제는 의도적으로 BLOCKED다. 자격증명·JWT·DOB·학생 데이터·메일 본문·실수신 주소를 manifest에 넣지 않는다.

## 2. SES 확인 근거와 최소 조회

### 2026-10-05 10:58 UTC 증거 갱신

부모가 현재 DEV enabled=true/provider=logging, From=no-reply@uteum.com, 서울(ap-northeast-2)을 확인했다. 사용자 콘솔에서 서울 계정 정상·quota 50,000/day/14/sec·uteum.com verified/DKIM success enabled를 직접 확인했고, 사용자 본인이 위 From→APPROVED_INBOX_1 콘솔 1건 발송 후 10:58 UTC 수신을 보고했다. EC2 역할의 GetAccount/GetEmailIdentity는 AccessDenied여서 권한을 바꾸지 않았다. **콘솔 수신 1건은 앱 역할 SendEmail·가입 fragment·명시 confirm 인수가 아니다. 앱 실제 발송은 0건이다.**

이 갱신은 아래 초기 준비표의 region/from/identity 미확인을 좁히며, 기존 예제 manifest는 초기 작성 값이라 null을 현재 관찰 증거와 혼동하지 않는다. 전역 provider를 바꾸면 기존 READY/RETRY와 다른 producer에 영향을 준다. 현재 설정을 유지하고 기존 SES 컴포넌트만 별도 child로 시험하는 [TEST 1건 경로·영속 사용 기록](ses-component-trial.md)을 준비했다. 실제 권한/설정 전환·추가 발송은 승인되지 않았다. [DEV 로그 보존 metadata 계획](dev-log-metadata-plan.md)은 독립 read-only 경계이며 현재 보존 14일·prod 상태를 확정하지 않는다.

| 구분 | 현재 확인 가능한 사실 |
| --- | --- |
| 저장소 기본값 | application/base Compose는 `AWS_REGION=ap-northeast-2`, From `no-reply@uteum.com`, base URL `https://dev.uteum.com`, provider `logging`이다. DEV workflow도 함께 읽는 `docker-compose.prod.yml` override는 미설정 provider/base URL을 `ses`/`https://www.uteum.com`으로 바꾸므로 실제 DEV의 명시 설정을 반드시 확인한다. 어느 기본값도 실제 runtime/SES 인증 완료 증거가 아니다. |
| SDK | `MailConfig`는 명시 region과 AWS DefaultCredentialsProvider를 사용한다. SES SDK 자동 재시도는 1회 호출이며, worker는 알려진 429만 최대 3회 시도한다. 새로운 key를 등록하거나 credential chain을 바꾸지 않는다. |
| IAM 문서 | `infra/iam/ec2-ses-policy.json`은 `<AWS_ACCOUNT_ID>` placeholder이며 domain identity와 From 제한 제안이다. 역할에 실제 부착됐거나 컨테이너에서 사용할 수 있다는 증거가 아니다. |
| 과거 DEV readback | 부모가 전달한 enabled=true/provider=logging/baseURL=dev 결과가 있다. 시각·freshness가 불충분하므로 현재 설정으로 재사용하지 않는다. Logging SENT는 가짜 provider receipt이며 실제 수신이 아니다. |
| 실제 미확인 | region/from 최신 값, SES identity/DKIM, SendingEnabled·sandbox·quota, 앱 역할의 SendEmail 권한/credential 접근, 현재 요금제와 수신 주소 승인이 모두 미확인이다. |

승인된 DEV 셸에서 아래 **첫 블록 하나만** 조회하면 mail provider/region/from/base URL의 blocker를 좁힐 수 있다. shell trace(`set -x`), `.env` 전체 출력, 전체 `docker inspect`, IMDS credential 조회를 사용하지 않는다. Compose는 container_name을 고정하지 않는다. 첫 조회의 DEV main-service 이름으로 `ACTUAL_DEV_MAIN_CONTAINER`를 바꾸며 여러 후보가 있으면 실제 DEV 대상을 먼저 확인한다. 이 조회는 runtime env만 보므로 Spring command-line/property override가 있다면 비밀값 없이 동일 항목의 적용값을 운영자가 별도 확인한다.

```bash
docker ps --filter label=com.docker.compose.service=main-service --format '{{.Names}}'
docker exec ACTUAL_DEV_MAIN_CONTAINER sh -c '
  for name in EDUPILOT_MAIL_ENABLED EDUPILOT_MAIL_PROVIDER EDUPILOT_MAIL_FROM EDUPILOT_MAIL_BASE_URL AWS_REGION; do
    printf "%s=" "$name"
    printenv "$name" || true
  done
'
```

그 결과의 region/from을 확인한 **기존 권한의 운영자 CLI**에서 다음 두 GET을 한다. 아래 서울/domain 값은 저장소 기준 예제다. 실제 값이 다르면 사용하지 않는다. AccessDenied면 설정 미확인으로 남기고 이 작업에서 IAM/identity/DNS/sandbox 변경을 실행하지 않는다.

```bash
aws sesv2 get-account --region ap-northeast-2 --no-cli-pager \
  --query '{SendingEnabled:SendingEnabled,ProductionAccessEnabled:ProductionAccessEnabled,SendQuota:SendQuota}' --output json
aws sesv2 get-email-identity --region ap-northeast-2 --email-identity uteum.com --no-cli-pager \
  --query '{Verified:VerifiedForSendingStatus,DkimStatus:DkimAttributes.Status,DkimSigning:DkimAttributes.SigningEnabled,MailFromStatus:MailFromAttributes.MailFromDomainStatus}' --output json
```

domain 인증이 성공하면 그 domain의 From 주소가 포함될 수 있어 개별 주소 identity 404만으로 미인증을 단정하지 않는다. sandbox인 경우 승인 수신 주소도 그 region의 verified identity여야 한다. simulator 수락은 사용자 inbox 수신 증거가 아니다. 앱 실행 역할의 비밀값 없는 ARN/정책 연결 증거를 운영자가 확인해야 하며, 다른 운영자 CLI의 SES GET 성공은 앱 SendEmail 권한을 증명하지 않는다. IAM simulation도 모든 resource policy/SCP/실행 경로를 보장하지 않는다. 권한이 이미 준비된 경우에만 별도 승인된 최소 실제 수신 시험으로 경계를 확인한다. [region별 SES 경계](https://docs.aws.amazon.com/ses/latest/dg/regions.html), [계정 GET](https://docs.aws.amazon.com/cli/latest/reference/sesv2/get-account.html), [identity GET](https://docs.aws.amazon.com/cli/latest/reference/sesv2/get-email-identity.html).

### 최소 발송 제안과 비용

부모가 사용자 확정 수신 주소 **1개**를 전달했다. 주소 원문과 요청 참조는 비공개 인계 자료에만 기록하고 공개 저장소에는 `APPROVED_INBOX_1`로 표시한다. 이 주소 선택은 SES 활성화·실제 발송 승인·다른 alias/수신자 허용이 아니다. 현재 실제 발송 승인은 없으며 이번 발송은 **0건**이다. 시험에 필요한 계정이 기존 사용자 계정이면 임의 가입·reset·탈퇴하지 않는다. 승인된 disposable fixture와 소유 관계를 먼저 확인한다.

| 제안 단계 | 최소 발송 | 확인 가능한 범위 |
| --- | ---: | --- |
| 가입 확인 최소 | 1 | 후보가 만든 fragment 메일 수신→페이지 열기→명시 confirm→본인 status. 사용한 링크 재확정 실패 확인은 추가 발송 없이 가능. |
| 재발급까지 | 2 | 위 1건 + 수동 재발급 1건. 새 링크 확인·이전 링크 거부. |
| 기존 연결 3종 | 4 | 위 2건 + password-reset 1건 + 탈퇴 완료 1건. 승인 합성 계정 안에서만 실행. 신규 Google 메일까지 실수신하려면 추가 1건/총 5건. |

독립 admin TEST/simulator 1건은 transport-only 선택이며 위 인수에 필수로 더하지 않는다. 202/SENT/SES message ID와 실제 inbox 수신 시각을 구분한다. 링크·token·메일 원문은 증거에 첨부하지 않는다.

2026-10-05 [공식 가격](https://aws.amazon.com/ses/pricing/) 기준 à-la-carte는 $0.10/1,000 recipient, Essentials/Pro/Enterprise 첫 구간은 각각 $0.16/$0.22/$0.23/1,000이다. 따라서 **기본 outbound 1~5건의 추가분은 $0.00010~$0.00115**, 권장 2건은 $0.00020~$0.00046로 계산된다. 첨부 없는 현재 SDK 요청을 전제한다. 무료 혜택·환율/세금·EC2 전송·현재 요금제/VDM/validation/고정 구독료는 미확인으로 이 범위에 넣지 않는다. 새 요금제/전용 IP/추가 기능을 켜지 않는다. simulator도 일반 outbound 요금 대상이다.

이 건수는 **시험 제안이지 runtime hard cap이 아니다**. 현재 코드에는 receiver allowlist나 시험별 1/2건 hard cap이 없고 수신자당 5회/시간·전체 KST 500회/일 예약만 있다. signup pause도 reset/notification 등 다른 mail producer를 멈추지 않는다. shared DEV에서 provider만 바꾸면 비용/수신 범위를 보장할 수 없다. 운영자는 기존 대기·다른 producer 영향과 격리된 합성 sender 실행 경계를 먼저 결정해야 한다. 확인 전 전역 SES 전환/메일 발송을 하지 않는다.

### 기존 outbox 영향

[메일 outbox](mail-outbox.md)와 [집계 SQL](qa/launch-ops/readonly-counts.sql)을 대조한다. DB GET 권한이 준비됐을 때만 실행하며 본문/주소/token/암호화 키를 조회하지 않는다. V54 테이블이 없으면 후속 집계를 생략하고 `unknown/not-applied`로 남긴다.

- V54 이전 QUEUED metadata-only 행은 migration에서 `FAILED/LEGACY_PAYLOAD_UNAVAILABLE`로 전환된다. SENT/FAILED/RATE_LIMITED 이력과 과거 시도 quota 예약은 보존된다. 실제 재발송 성공으로 표시하지 않는다.
- V54 이후 READY/RETRY는 최종 text/HTML을 암호화해서 보존한다. worker는 현재 provider를 사용하며 생성 시 provider를 고정하지 않는다. SES 전환은 기존 작업도 발송할 수 있다. 새 fragment 생성 코드가 기존 query 본문을 변환하지 않는다. metadata의 시각/type만으로 링크 형식을 확정할 수 없다.
- 만료 인증 본문은 정리되며 SENDING 결과 불명은 UNKNOWN으로 수렴한다. UNKNOWN을 자동 재전송하지 않는다. Logging SENT도 SES로 자동 재발송하지 않는다. 필요한 본인 재발급은 별도 승인된 주소·횟수 안에서 수행한다.
- encryption key 또는 JWT 기반 HKDF의 기존 secret 관리 버전을 유지한다. 존재하는 payload에 새 키를 임의 도입하지 않는다. 값·hash를 추출하지 않고 승인된 secret version 참조와 운영자 연속성 확인만 기록한다.
- 과거 query 대기 건의 취소/만료/재발급, 비시험 대기 건, quota 잔량 처리 방침은 결정 필요다. 행 삭제·본문 덤프·직접 VERIFIED 업데이트·새 provider로 무차별 회수하지 않는다.

## 3. 같은 작업 창의 pause·재개와 취소

**pause 시작 전** FE의 최종 ON source/build/artifact와 최신 SES GET/권한·수신 인수 승인, 대기 outbox 처리, 담당자와 취소 경로가 함께 준비돼야 한다. 현재는 이 조건이 충족되지 않았다. 준비되지 않은 상태에서 가입을 먼저 닫지 않는다.

1. 부모/운영/FE가 동일 manifest를 고정한다. 담당자, 허용 중단 범위·최대 창과 보고 기준, 현재 source/config/image/FE 원본, migration 진입 전 취소 담당을 실제로 지정한다. 날짜·시간은 여기서 약속하지 않는다.
2. 승인된 짧은 전환 시 Nginx marker pause의 실제 503을 확인한다. Google 기존 로그인도 해당 경로에 포함된다. 일반 로그인·legacy 업무/health는 계속되는 제한이며 전체 DB 쓰기/메일 freeze로 취급하지 않는다.
3. 실제 migration 직전에 접근 제한된 새 일관성 backup과 무결성·복원 계획을 기록한다. `--single-transaction` 시 DDL 동결/대상 InnoDB 확인이 필요하며 별도 COUNT 조회를 동일 snapshot으로 오인하지 않는다. 삭제 원장은 backup보다 최신 증거를 별도로 보존해야 한다. 기존 2026-10-04 backup을 덮어쓰지 않는다.
4. 최종 승인 image/config/FE artifact로 전환한다. develop push가 자동 DEV 배포와 Main startup Flyway를 일으키므로 이것이 별도 승인 대상 실행 경계다. source CI의 success로 migration 성공을 대신하지 않는다.
5. 준비된 합성 계정/세션 범위에서 legacy 증거 불변, LOCAL/기존 Google, 신규 DOB/동의 오류, 이메일 pending/guardian pending 업무·파일·SSE 차단, 비대상 신규 접근과 타인 접근 차단, 본인 정정 접수를 인수한다. guardian 통과 계정을 조작하거나 유료 AI를 호출하지 않는다. 실제 mail 단계는 따로 승인된 경우에만 포함한다.
6. 실제 image digest·V60/Flyway·FE/정책·메일 수신 결과와 재개 담당을 확인하고 marker를 해제해 재개한다. 해제 뒤 정상 응답과 미확인 이용 차단을 다시 확인한다. 준비된 조건과 달라지면 사용자에게 보고하고 오래 닫아두는 결정을 임의로 하지 않는다.

## 4. V60 정리 hook을 보존하는 복구

V60은 새 테이블만 추가하지만 이전 Main은 그 요청 DOB를 탈퇴 시 지우지 않는다. V60을 넣은 뒤 테이블이 구 코드에서 무시된다는 이유로 이전 image를 자동 재기동하지 않는다. 전역 업무/email/SSE/늦은 턴 gate와 삭제/guardian hook도 복구 artifact에 보존해야 한다.

| 중단 시점 | 준비할 복구 경로 |
| --- | --- |
| Main migration 진입 전, DB 변경 없음 확인 | 저장된 원본 Nginx/Compose와 기존 FE/source 조합으로 nginx-only 취소·재개. 새 source 복사 또는 marker 유실에 주의. 이 경로를 post-migration rollback에 재사용하지 않는다. |
| V60/Flyway 적용 이후 | 검증한 후보의 gate·V60 hook을 보존한 roll-forward를 우선한다. 장애 부분만 교정한 draft/정확 CI/합성 회귀를 준비한 뒤 운영 승인으로 적용. 임의 down migration/테이블 삭제 없음. |
| 이전 runtime 기능 복귀가 불가피 | 단순 old image가 아닌 호환 patch가 필요하다. 현 gate들과 `BirthdateCorrectionWithdrawalHook`, 같은 User 잠금/동일 트랜잭션, DOB null/WITHDRAWN·실패 rollback을 보존하는 image를 별도 검증한다. 현재 그런 old-image 호환 artifact는 없다. |
| DB restore가 불가피 | 대상/데이터 손실·새 쓰기·외부 receipt·삭제 원장 영향에 별도 승인. 현재 안전 snapshot을 먼저 보존하고 traffic/worker를 멈춘 유지보수 경계에서 실행한다. 이 문서는 실제 복원 명령을 실행하지 않는다. |

현재 `DeletionReplay`의 ACCOUNT APPLIED 경로는 User identity(생성시각/email hash)를 대조하고 User 잠금 아래 withdraw→flush→현재 `UserWithdrawalHook` 목록을 실행하므로 V60 hook도 같은 transaction에 참여한다. `DeletionRestoreService`는 외부 보존된 trusted deletion snapshots를 import/reapply한다. 자동 startup 복원이나 공개 실행 API는 없다. 실제 별도 보관 원장의 위치·진본성·운영 호출 adapter는 미확인이다.

ALREADY_APPLIED는 hook을 다시 실행하지 않는다. 복원 전후 [집계 SQL](qa/launch-ops/readonly-counts.sql)의 `deleted_user_request_dob_leaks`가 0인지 확인한다. 0이 아니면 이전 code/부분 restore 경계를 조사하고 정리 hook이 있는 교정 artifact를 준비한다. 임의 DOB 보존·직접 UPDATE로 정상 처리하지 않는다. IDENTITY_MISMATCH·미완료 replay·새 쓰기 손실·checksum 실패·다른 backup이면 재개하지 않는다. 정상 전체 snapshot을 복원했더라도 backup 이후 탈퇴 원장의 재적용 완료와 refresh 폐기·DOB null/WITHDRAWN·외부 메일 UNKNOWN 재전송 방지를 확인해야 한다.

새 합성 회귀는 APPLIED 경로의 V60 DOB 제거, 반복 적용, identity mismatch 보존 및 hook 실패 시 전체 transaction rollback을 확인한다. 실제 운영 backup/restore·실계정 탈퇴를 검증했다고 확대하지 않는다.

## 5. 부모/FE에 보낼 결정 목록

| 계획 | 확정 | 제안 | 결정/증거 필요 |
| --- | --- | --- | --- |
| FE ON | BE521 KST/guardian/수정 요청 계약, 현재 FE OFF·경로 누락 | FE 담당자가 위 최소 source 변경 후 exact build/settings/artifact manifest 고정 | 최종 FE SHA·readiness 값/주입 PR·동일 head CI·ON artifact·인수 담당 |
| 실제 mail | 저장소 SES 구현/서울/From 예제, Logging는 실수신 아님 | 첫 env allowlist 조회→SES GET2개→최소1 또는 재발급 포함2건 인수 | 현재 실제 region/from/identity/IAM·요금제, 승인 inbox 참조·건수/비용·기존 outbox 처리/producer 격리 |
| 전환·재개 | 짧은 signup/Google pause만 승인, develop 자동 배포/Flyway | FE/BE 양쪽 artifact를 고정한 같은 창의 조건부 재개 | 담당자·허용 창/취소 시점·target image 준비 경로·fresh backup·역할별 인수 |
| V60 복구 | 일반 withdrawal 및 restore APPLIED에서 현 hook 참여 | migration 이후 gate/hook 보존 roll-forward | old-image 호환 artifact가 필요할지, 별도 trusted 원장/restore adapter·복구 담당·손실 허용/승인 |

guardian 업체·관계 증거·승인/철회, 관리자 DOB 정정 심사/반려/실적용과 metadata 보존기간은 별도 TBD로 유지한다. 위 준비 자료는 이 정책들을 대신 결정하거나 보호자 대상 가입을 업무 이용 가능으로 열지 않는다.
