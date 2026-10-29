# 서비스 메일 4건 시험의 재생성·중단 검토 (2026-10-08)

검토 후보는 [PR541](https://github.com/AutoAI-UTEUM/BE/pull/541) head `58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e`, tree `8404485ab66793764212d05da739bc66b81fc12e`다. 이번 작업은 코드와 기존 증거를 읽고 대안을 검토한 문서 작업이다. 현재 실행 승인안·runtime·서버·DB·FE·메일 설정은 변경하지 않았다.

**현재 조건을 보존하면 main 재생성 9회를 줄일 이미 구현된 서비스 발송 경로는 확인되지 않았다.** 독립 CLI는 TEST 전용이다. 직전 ID가 확정적으로 종결됐을 때 중간 PAUSED/logging 복귀를 생략하는 정상 경로 6회 대안은 아래에 검토만 기록한다. 현재 승인안의 정지 조건을 바꾸므로 실행 가능·승인·실인수 완료로 표시하지 않는다.

## 1. 보존하는 승인과 실행 경계

커밋 밖 로컬 인계 자료 `evidence/service-mail-four-trial-20261007/DEPLOY-AND-ISOLATED-TRIAL-PLAN.md` 및 `approval-bundle.json`의 기준은 다음과 같다. 공개 준비 경계는 [DEV 복구 인계](dev-deployment-recovery-handoff-20261008.md)와 [현재 TEAM 운영 준비](../launch-team-operational-readiness.md)에서 확인한다.

- 서비스 provider 호출 최대 4건, 각 차수 새 **1-ID / 정확한 수신자 1명 / provider 시도 최대 1회**다. 승인된 exact plus 주소는 비공개 참조로만 사용한다.
- 성인 LOCAL LEARNER 합성 계정 1개, DOB1990-01-01, 사용자 직접 비밀번호 입력, 가입→재발급→reset→논리 탈퇴 순서다. optional AI/학습메일과 물리 삭제는 OFF다.
- 이전 SDK identity 1회·TEST 1건 예산은 이미 소진됐다. trialId 변경·claim 삭제·추가 TEST·identity 조회로 이 예산을 재사용하지 않는다.
- 개발 배포·V54~63·private DEV 복구 지점·실효 설정 변경의 실행 승인은 미확정이다. 이미 승인된 메일 건수/수신자/성인 계정 조건을 다시 묻지 않는다.
- 이번 검토의 서버/SSH/실 DB/실메일/STS·SES 호출/배포/FE 변경/커밋·push는 모두 **0**이다. 서비스 메일 사용은 **0/4**다.

## 2. standalone CLI가 확인하는 범위

| 구현 | 실제 경계 | 4종 서비스 시험 대체 |
| --- | --- | --- |
| [IsolatedSesTrial](../../main-service/src/main/java/io/edupilot/mail/IsolatedSesTrial.java) 19~20, 87~110행 | Spring·HTTP·DB·outbox를 시작하지 않고 고정 TEST 제목/본문을 SesEmailSender로 1회 보낸다. 가입/reset token은 없다. | 불가. 실제 producer·암호화 payload·DB claim·quota·token 확정을 검사하지 않는다. |
| [run-once.sh](../../scripts/qa/mail-trial/run-once.sh) 52~73, 75~117행 | 승인 JAR/manifest hash와 컨테이너 ID·image·Compose metadata를 대조한다. host 영속 claim을 먼저 소비하고 child loader/Java 옵션을 고정한다. 실행 중인 main provider/worker는 재설정하지 않는다. | staging·별도 child 실행은 있지만 서비스 outbox dispatch가 없다. 이미 소비한 TEST 승인을 사용할 수 없다. |
| [native bootJar plan 검사](../../scripts/qa/mail-trial/check-boot-jar-plan.mjs) 48~71행 | 합성 plan 2건과 거부 입력 4건. SpringStarted=false, sends=0, identityAttested=false, executionAuthorized=false다. | 패키징 진입/거부 증거다. live sender·현재 image·서비스메일 성공 attestation이 아니다. |
| [AdminMailController](../../main-service/src/main/java/io/edupilot/admin/mail/AdminMailController.java) 55~85행 | TEST 생성과 이력 조회만 제공한다. 이미 저장된 서비스 ID를 발송하거나 dispatch 설정을 교체하는 API는 없다. | 추가 TEST를 만든다면 4건 범위를 벗어나며 대체가 아니다. |

해당 후보의 main 메서드는 MainServiceApplication과 IsolatedSesTrial 두 곳이다. outbox를 읽고 같은 transaction/claim gate를 사용하는 서비스용 독립 main·worker 전용 CLI는 없다. PropertiesLauncher로 기존 Spring main을 실행하거나 web mode만 끄면 Spring/Flyway와 전체 component·scheduler를 기동한다. [ExamGradingConfig](../../main-service/src/main/java/io/edupilot/exam/ExamGradingConfig.java) 10~11행이 전체 scheduling을 활성화한다. 이를 검증된 mail-only 경로로 간주하지 않는다.

## 3. 현재 서비스 발송의 안전 경계

| 경계 | 소스 근거·판정 |
| --- | --- |
| 업무 producer와 durable payload | [EmailVerificationService](../../main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java) 40~52, 79~86행은 가입/재발급 token과 동일 30분 expiry를 저장한다. [PasswordResetService](../../main-service/src/main/java/io/edupilot/auth/PasswordResetService.java) 75~118행도 정확한 expiry를 전달한다. [UserService](../../main-service/src/main/java/io/edupilot/user/UserService.java) 213~258행은 논리 탈퇴/세션 폐기 후 원래 수신자를 사용한다. |
| 커밋 경계 | [EmailService](../../main-service/src/main/java/io/edupilot/mail/EmailService.java) 62~94행과 [EmailOutboxStore](../../main-service/src/main/java/io/edupilot/mail/EmailOutboxStore.java) 37~50행. 이력은 REQUIRES_NEW, payload는 호출자 transaction에 참여하며 afterCommit 뒤 kick한다. 롤백된 payload를 외부로 보내지 않는다. |
| PAUSED | enabled=true여야 신규 payload를 보존한다. PAUSED는 회수/direct kick/claim 발송을 막는다. 만료·lease 정리 DB 쓰기는 계속된다. enabled=false는 새 메일을 DISABLED로 종결하므로 같은 준비 경로가 아니다. |
| 정확한 승인 범위 | [EmailDispatchProperties](../../main-service/src/main/java/io/edupilot/mail/EmailDispatchProperties.java) 20~50행은 ISOLATED_TRIAL에 1~3개 양수 ID/수신자 1명을 요구한다. 이 계획은 허용 최대와 별개로 매 차수 **1개**만 선택한다. metadata와 복호화 payload 수신자 둘 다 일치해야 한다. |
| 한 ID의 시도 | outbox claim 및 beginSending에서 양쪽 attempt_count=0, QUEUED·READY/RETRY, expiry·lease/fencing을 다시 확인하고 SENDING/attempt1을 별도 DB transaction으로 먼저 기록한다. ISOLATED_TRIAL의 확정 429도 추가 provider 시도를 허용하지 않는다. |
| quota | [EmailDeliveryStore](../../main-service/src/main/java/io/edupilot/mail/EmailDeliveryStore.java) 41~70행은 migration singleton/global lock과 영속 reservation으로 최근1시간 수신자5회·KST일 전체500회를 공유한다. singleton이 없으면 차단한다. 예약 뒤 호출 전 실패도 예약을 돌려주지 않는다. 이 한도는 승인된 총4건 예산을 대신하지 않는다. |
| sender·재시도 | [MailConfig](../../main-service/src/main/java/io/edupilot/mail/MailConfig.java) 34~59행은 기존 DefaultCredentialsProvider, Seoul region, SDK maxAttempts=1·10초 timeout을 사용한다. [SesEmailSender](../../main-service/src/main/java/io/edupilot/mail/SesEmailSender.java) 28~54행은 429/확정4xx와 불명확5xx·transport를 구분한다. |
| SENT·UNKNOWN | [EmailOutboxWorker](../../main-service/src/main/java/io/edupilot/mail/EmailOutboxWorker.java) 72~99행은 claim→reservation→SENDING→provider→receipt를 따른다. 중단/transport/receipt 저장 실패는 SENDING→UNKNOWN으로 수렴하며 payload를 제거하고 자동 resend하지 않는다. 동일 fencing token의 실제 늦은 receipt만 SENT 정정을 허용한다. SENT는 실제 inbox 도착·token 확정과 별개다. |
| 다른 worker·producer | DB claim은 같은 ID의 경합을 제한한다. 다른 NORMAL worker가 다른 ID를 보내는 것을 한 인스턴스의 ISOLATED_TRIAL로 막을 수는 없다. 같은 DB 전체 인스턴스/실효 설정·inflight를 확인해야 한다. PAUSED에서 다른 producer가 만든 READY payload도 나중에 NORMAL로 돌아가면 회수될 수 있으므로 NORMAL 전환은 제외한다. |
| key 연속성 | [EmailPayloadCipher](../../main-service/src/main/java/io/edupilot/mail/EmailPayloadCipher.java) 28~54행은 전용 key가 없으면 기존 JWT로 mail 전용 HKDF-SHA256 AES key를 유도한다. 배포/재생성/모든 worker에서 같은 secret 관리 버전을 유지한다. 값을 출력·hash 추출·새 key 생성·JWT 변경하지 않는다. |

설정은 @ConfigurationProperties **record**와 생성자 주입의 final 참조, provider 선택은 시작 시 conditional bean이다. 런타임 reload API·RefreshScope·Spring Cloud refresh 의존성은 없다. Actuator web endpoints도 [application.yml](../../main-service/src/main/resources/application.yml) 33~37행에서 모두 제외한다. .env만 수정하거나 docker exec의 자식 환경을 바꿔 실행 중 main의 bound bean이 갱신됐다고 할 수 없다. Compose restart도 환경 변경을 반영하지 않는다. [Docker 공식 설명](https://docs.docker.com/reference/cli/docker/compose/restart/)

**기본 mode는 NORMAL**이다. 미설정 값을 PAUSED 또는 전체 queue 격리의 증거로 쓰지 않는다. 실제 candidate 기동 전에 PAUSED/빈 승인 목록을 명시하고 resolved Spring·다른 worker를 확인해야 한다. logging+NORMAL은 provider를 모의 성공/SENT로 기록하고 payload를 제거하므로 실제 서비스 수신 시험 준비로 쓰지 않는다.

## 4. 재생성 횟수 비교

| 경로 | main 정상 경로 횟수 | 판정 |
| --- | --- | --- |
| 현재 계획 | 배포1 + 차수별 진입4 + PAUSED/logging 복귀4 = **9** | 매 건 뒤 명시적 정지/빈 승인 복귀를 보존한다. provider와 mode/ID/recipient를 한 번에 적용하므로 불필요한 provider-only 재생성은 이미 제외했다. |
| 같은 PAUSED 경계에서 provider만 SES 유지 | **9** | mode/ID/recipient의 immutable 입력 변경이 여전히 필요하다. 횟수 이득이 없고 종료 provider 조건도 바꾼다. |
| standalone TEST CLI | service 재생성0처럼 보일 수 있음 | outbox/서비스 workflow를 검사하지 않으므로 4건 계획의 대체 불가. 소진 예산의 재실행도 불가. |
| 2~3-ID 묶음 또는 미래 ID 선승인 | 줄어들 수 있음 | 현재 1-ID·순차 token/UI 인수와 다르다. 미생성 ID 추정/승인 또는 4-ID 설정은 불가하다. |
| 별도 Spring/Compose child를 mail-only로 추정 | 계산하지 않음 | 독립 worker entrypoint·다른 scheduler 차단·기존 DB/config 격리 증거가 없다. 새 runtime 설계/조작에 해당하며 이번 검토에서 진행하지 않는다. |
| 직전 SENT-ID 유지 → 다음 신규 1-ID로 직접 교체 | 배포1 + 진입1 + ID교체3 + 최종 PAUSED/logging1 = **6** | 아래 조건부 미채택 대안이다. 기존 코드의 gate에서 추론했으며 설정 전환 시나리오·실효 runtime 검증 완료가 아니다. |

6회 대안은 **모든 이전 차수의 outbox·delivery가 SENT, 각 attempt1, payloadNULL, 같은 SES sender의 실제 수락 근거와 inbox/UI 결과가 확인되고 inflight·UNKNOWN이 없을 때만** 검토 가능하다. 사이 구간에는 provider=ses/mode=ISOLATED_TRIAL/직전 소진된 1-ID가 남는다. 그 ID는 더 이상 발송 대상이 아니며 새 ID와 타 recipient는 차단된다. 다음 신규 ID는 별도로 사용자 정상 흐름에서 생성·owner/type/수신자/expiry/attempt0/quota를 확인한 뒤 교체해야 한다.

이는 PAUSED 자체가 아니다. 명시적 빈 승인 목록과 logging으로 복귀하는 세 중간 구간을 생략하므로 기존 승인안을 그대로 실행하는 경로가 아니다. 모든 worker의 설정 전환·주기적 cleanup과 경합·중단 후 정지 절차를 검토하고 별도 승인안에 반영하기 전에는 채택하지 않는다. 실패·429·UNKNOWN·receipt/inbox 불명확·token 만료·quota 부족이면 새 ID로 넘어가지 않고 PAUSED로 중단하며 추가 시도·대체ID로 성공을 만들지 않는다. 실패 경로 횟수를 6회로 약속하지 않는다.

**추천:** 현재 PAUSED 경계를 보존한 9회 안을 유지한다. 중단 횟수가 중요한 경우 위 6회 대안을 하나의 구체적인 운영 선택으로 부모 통합 결과에 제시할 수 있다. zero-restart 서비스메일 대체 CLI가 이미 완성됐다고 설명하지 않는다.

## 5. image·CI·복구 근거의 한계

최신 보존된 실제 DEV 관측은 **2026-10-07 07:09:40 UTC 사용자 snapshot**이다. source b5f659bf223daff2a4cbda7b36c2fbae543494e7, image ID sha256:7f430cd1bba358aa9dd9dd7b6a07751c2ccb2bb3cdfdff9e2fe6a50121001d07, Flyway53/outbox·reservation 부재였다. 2026-10-08의 현재 live image로 재확인한 결과가 아니다. 후보58df의 실제 배포 image digest·full container ID·bound Spring·schema·quota는 미확인이다.

후보와 PR539461b 사이의 mail runtime·CLI·Compose·Dockerfile·DEV deploy workflow·migration diff는 없다. 같은 후보 [Main CI37595588035](https://github.com/AutoAI-UTEUM/BE/actions/runs/37595588035)와 [AI CI37595588034](https://github.com/AutoAI-UTEUM/BE/actions/runs/37595588034)는 성공했다. 로컬 bootJar hash는 `46a7ab9195136e06d732285088bc63ae5fc2393d614dc922f975713883c04891`, CI bootJar hash는 `a21aaeb3a4d20b69c717dab19bd13a2f90b80be86b66722b1c3157f02c7836f0`다. 별도 환경 산출물을 동일 binary나 DEV image로 취급하지 않는다. [DEV workflow](../../.github/workflows/deploy-dev.yml) 59~77행은 반영 SHA의 image를 별도로 build한다.

실행 승인 뒤 운영자가 release merge SHA/tree→실행 workflow→main/AI image digest→배포 container/image→실제 app artifact/설정을 연결해야 한다. 기존 standalone wrapper의 JAR/source 검증은 staged TEST artifact에 관한 것이며 실행 중 main의 source까지 자동 증명하지 않는다. 예전 b5 고정 읽기 wrapper를 새 candidate에 그대로 쓰지 않는다.

초기 DEV workflow는 all-service pull/up --remove-orphans와 Nginx restart를 포함하며 mutable MySQL/Nginx image도 영향을 받을 수 있다. 이후 trial 변경은 승인된 고정 image의 main만 적용하는 현재 계획을 보존한다. 실제 중단 시간·기동/health/API/SSE 유지 시간을 로컬 코드만으로 보장하지 않는다. initial rollout/backup/migration 영향을 6회 또는9회 main 숫자에만 축소하지 않는다.

PAUSED는 이미 시작한 provider 호출을 취소하지 못하며 UNKNOWN을 성공으로 바꾸지 않는다. V54 quota backfill·reservation/attempt/기존 SENT를 삭제하거나 초기화하지 않는다. DB backup을 되돌리면 외부 SES 효과를 되돌릴 수 없고 발송 이력이 복원점 뒤에서 사라져 재전송 위험이 생긴다. 자동 restore/down migration은 제외한다. PENDING/NEW_SIGNUP 보호가 없는 b5 단순 rollback도 금지하며, guard를 유지할 검토된 서비스 복구와 별도 데이터 복구 승인을 구분한다. private 복구 지점·압축/checksum 검증·기존 restore 경로 증거를 실제 복원 PASS로 표시하지 않는다.

## 6. 기존 테스트 재사용과 남은 결정

이번에는 소스·문서만 검토했으며 Gradle/Node/native bootJar 기존 검사를 재실행하지 않았다. 커밋 밖 로컬 `evidence/signup-policy-readiness-20261007/completion.json`의 final-full-reports에서 해당 suite/case를 직접 대조했다. 공개 변경·검증 요약은 [PR541](https://github.com/AutoAI-UTEUM/BE/pull/541)과 [서비스 메일 합성 인수](mail-flow-acceptance-20261007.md)를 함께 본다.

| 기존 suite | PASS / SKIP | 보장하는 합성 경계 |
| --- | --- | --- |
| EmailDispatchPropertiesTest | 14 / 0 | 잘못된 ID/recipient/mode 조합 기동 실패, NORMAL 기본값 |
| EmailOutboxDispatchIsolationJpaTest | 6 / 0 | metadata+payload recipient, direct/recovery, parallel worker 1회, 확정429 재시도 차단, 양쪽 attempt |
| EmailOutboxPausedJpaTest | 3 / 0 | 신규 transaction payload 보존·발송/예약0, 만료 정리, 롤백 |
| ServiceMailDispatchLifecycleJpaTest | 1 / 0 | 실제 API 합성 가입→확인→reset→탈퇴, 비시험 backlog 보존 |
| EmailOutboxRecoveryIsolationTest | 4 / 0 | 행 실패가 cleanup/다음 회수를 막지 않음, secret 로그 억제 |
| EmailOutboxJpaTest | 16 / 1 | claim/fencing/경합/quota, SENDING→UNKNOWN, receipt 저장 실패, 커밋/롤백·포화. 실제 MySQL migration case는 SKIP |
| EmailOutboxWorkerTest / EmailPayloadCipherTest / EmailDeliveryStoreTest | 8+3+5 / 0 | 불명확 provider/receipt, 같은 key의 프로세스 연속성·AAD 변조, 영속 한도 |
| IsolatedSesTrialTest | 50 / 0 | mock SDK TEST/identity 분리·claim·범위/출력 차단 |
| MailFlowAcceptanceJpaTest / MailFlowAcceptanceResetDeadlineTest | 8+2 / 0 | 재발급/구 query/token·payload 동일 expiry/논리 탈퇴 경계 |

합계는 기존 XML **121개 중120 PASS / 1 SKIP / 실패0·오류0**다. 이 합계를 오늘 새 시험 결과로 표시하지 않는다. Node wrapper27 PASS와 native plan6 PASS도 완료된 증거만 참조했다. 현재 DEV MySQL·SES·mailbox·FE UI·6회 설정 전환 시나리오는 실검증 결과가 없다.

부모가 한 번에 묶을 남은 실행 조건은 최종 정책·유효 required 문서, FE readiness/정상 UI, private DEV 복구 지점/담당자, 기존 보호규칙을 지킨 release 배포·V54~63·선택 설정 변경 승인, 이후 같은 image/key·전체 worker·quota·신규 ID attestation이다. service4건/plus수신자/성인LOCAL1/사용자 직접 password는 확정된 사실로 유지한다.

6회 대안을 선택한다면 추가로 필요한 구체적인 선택은 **“각 성공 차수의 SENT/수신/UI 확인 후, 중간에는 직전 소진된1-ID의 SES/ISOLATED_TRIAL을 유지하고 다음 신규1-ID로 직접 전환하는 방식으로 변경할 것인가”**다. 이 선택을 현재 진행 승인이나 기존4건 예산 승인으로 간주하지 않는다. 배포·DB/설정 쓰기·실메일을 요청한 이번 작업은 없으며 승인 초안도 수정하지 않았다.

외부 개념은 2026-10-08 공식 문서와 대조했다. SES MessageId는 수락 식별자이고 수락 후 미발송 가능성도 명시돼 있다. [SES SendEmail](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html) 공개 요청 schema에 client idempotency token이 없다는 확인과, 이 후보가 그런 키를 보내지 않는다는 소스에 따라 UNKNOWN 자동 resend를 안전 경로로 권하지 않는다. 이는 외부 결과를 확인한 증거가 아니다. SDK maxAttempts는 최초 요청을 포함하므로 설정1은 retry0에 해당한다. [AWS SDK retry 설명](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html)

상세 source hash·기존 XML hash·비실행 검증은 커밋 밖 로컬 `evidence/policy-review-20261008/mail-review/review.json`에 보존한다. 이 비공개 인계 원장을 공개 저장소에 복사하거나 공개 링크로 가장하지 않는다.
