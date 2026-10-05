# 격리된 SES 컴포넌트 시험 준비 (#473, #471, #479)

브랜치 한 줄: `feature/473-isolated-ses-trial` — 동일 SDK 설정의 STS identity 조회와 SES TEST 1건을 별도 승인·예산·사용 기록으로 제한하는 실행 경로를 준비한다.

실제 발송·권한 변경·설정 전환·develop 배포는 이 준비의 범위에 없다. public 예제의 `owner@example.com`은 시험 데이터다. 실제 수신자는 사용자 확정 **한 주소**이며 비공개 manifest에서만 사용한다. 주소 선택과 콘솔 발송 승인은 아래 앱 발송 승인을 대신하지 않는다.

## 현재 증거와 미확인 경계

2026-10-05 10:59 UTC 부모가 전달한 운영 증거는 다음과 같다.

| 항목 | 확인된 사실 | 남는 경계 |
| --- | --- | --- |
| DEV 앱 설정 | provider=logging, enabled=true, From=no-reply@uteum.com, region=ap-northeast-2 | 현재 앱 실행 역할의 SES SendEmail 성공은 미확인이다. |
| 사용자 AWS 콘솔 | 서울 계정 정상, 표시 quota 50,000/day·14/second, uteum.com verified·DKIM success/enabled | 앱 역할의 credential chain이나 SendEmail 권한 증거가 아니다. |
| EC2 역할 SES GET | GetAccount·GetEmailIdentity는 AccessDenied. 권한을 바꾸지 않았다. | 조회 거부를 SendEmail 거부·CloudWatch 거부로 일반화하지 않는다. |
| 콘솔 수신 | 사용자가 위 From에서 APPROVED_INBOX_1으로 콘솔 1건을 보냈고 10:58 UTC 수신을 확인했다. | 앱 SDK·outbox·가입 링크·명시 confirm 인수는 미실행이다. |
| 이번 앱 시험 | 준비·로컬 모의 검증만 한다. 앱 실제 발송 0건. | 별도 실행 승인과 정확한 계정/실행 대상 attestation이 필요하다. |

일반 worker는 현재 provider로 READY/RETRY를 회수한다. 공통 발송 한도(수신자당 5/시간·전체 500/KST일), signup pause, logging SENT는 시험 수신자·건수 제한을 보장하지 않는다. 이 때문에 shared DEV의 provider를 SES로 전환해 시험하지 않는다.

## 실행 경로와 검증 범위

`IsolatedSesTrial`은 Spring bean이 아닌 별도 main이다. 기존 `MailConfig.sesV2Client`의 서울 리전·DefaultCredentialsProvider·SDK maxAttempts=1·10초 call timeout과 기존 `SesEmailSender`를 직접 사용한다. SpringApplication, HTTP, DB, 계정, 토큰, outbox producer/worker, 암호화 payload, migration을 기동하거나 호출하지 않는다. 실행할 때만 SDK client를 만든다.

실제 DEV main 컨테이너 안에 **검토된 새 JAR의 별도 프로세스**를 실행한다. `/app/app.jar`와 실행 중인 main 서비스는 그대로 유지한다. 별도 child의 loader/Java 옵션만 고정하며 mail provider·profile·credential 환경을 변경하지 않는다. 한 명의 To, TEST 종류, 고정된 합성 제목/본문, 첨부·CC·BCC·회신 주소·인증 링크 없는 1건이다. 기존 앱과 동일한 SDK credential chain을 사용하며 별도 access key/profile/role을 넣지 않는다.

이 경로가 확인하는 것은 **동일 DEV 컨테이너 환경의 기존 SES 컴포넌트→수신함**이다. admin HTTP, EmailService의 outbox 저장, worker 회수, 실제 signup/reset/withdrawal 호출, fragment 링크와 FE 명시 confirm은 후속 인수다. `ACCEPTED_BY_SES`는 provider 수락이며 실제 수신 성공은 사용자 확인 시각으로 따로 기록한다. 이메일 인증·보호자 확인 상태를 조작하거나 성공으로 생성하지 않는다.

## 검토 manifest와 1건 제한

[예제](qa/mail-trial/manifest.example.properties)는 placeholder와 빈 승인 참조로 실행을 막는다. 비공개 사본에는 최종 source SHA, 해당 build JAR SHA256, **full DEV main container ID**, image ID, Compose project, 승인된 하나의 recipient, 고정 From/region, maxMessages=1, 호스트 stateDirectory와 실행 승인 참조를 넣는다. 실제 주소·계정 참조·private manifest를 GitHub에 올리지 않는다. manifest는 ASCII 한 줄 key=value이며 중복/추가 key·여러 수신자·범위 변경을 거부한다.

`awsAccountId`는 승인할 계정이며 `awsIdentityEvidenceRef`는 SEND 전에 확보할 SDK 실행 identity 증거다. 계정 번호 필드만으로 credential 소유 계정을 증명하지 않는다. 호스트/컨테이너 CLI 결과를 Java SDK 증거로 대신하지 않는다. source SHA는 승인 JAR과 build 증거로 연결한다.

### SDK identity 전용 단계

2026-10-05 12:12 UTC 부모가 DEV full container/image ID, 기존 main의 `edupilot` UID/GID 10001, 컨테이너 CLI 미설치와 호스트 STS ARN을 전달했다. 실제 값은 비공개 인계 자료에만 보존한다. CLI를 설치하지 않고 별도 **identity staging/조회 승인**을 먼저 준비한다. 현재 staging·실제 STS·메일 모두 미승인/미실행이다.

`--identity`는 기존 SES factory와 같은 `DefaultCredentialsProvider.create()`, 서울 region, UrlConnectionHttpClient, 공통 SDK maxAttempts=1·10초 call timeout으로 STS `GetCallerIdentity`만 호출한다. SES client를 생성하거나 SendEmail·DB·큐·Spring을 호출하지 않는다. STS 모듈은 기존 AWS BOM 2.49.6에 고정한다. 두 모드는 이 **새 검토 JAR**에서 동일 설정을 사용하며 기존 실행 중인 다른 JAR의 SDK identity까지 증명하는 것은 아니다. credential profile/role/key를 새로 넣지 않는다.

identity manifest는 `approvedOperation=IDENTITY`, `maxMessages=0`, 승인 계정, 별도 identity 승인 참조와 고정 hash를 요구한다. identity에는 아직 없는 SDK evidence ref를 요구하지 않는다. 주소 필드는 미사용 예제값을 유지할 수 있다. 정상 출력은 **Account·Arn 두 필드만**이며 UserId·credential·token·본문·raw error/debug는 출력하지 않는다. 다른 계정이 반환되면 metadata를 보여주되 exit 2로 차단한다. 같은 계정의 다른 ARN도 운영자가 승인할 실제 role인지 별도로 대조한다. 이 결과는 메일 권한·수신·가입 완료가 아니다.

identity 사용 기록은 host `TRIAL_ID.identity-read`, container `/tmp/uteum-ses-trial-TRIAL_ID.identity.claim`이며 1회만 조회한다. SEND의 `.consumed`/`.claim`과 분리한다. 조회 실패에도 자동 재시도하지 않는다. 후속 SEND는 실제 SDK 증거 ref와 새로운 SEND 승인 manifest/hash, `approvedOperation=SEND`·`maxMessages=1`을 요구한다. identity 승인을 SEND에 사용하거나 SEND 승인을 identity에 사용하는 것은 거부한다. 기본 `NONE`·0건 예제와 모든 plan은 SDK client·네트워크·claim을 생성하지 않는다.

[run-once.sh](../scripts/qa/mail-trial/run-once.sh)의 기본 동작은 plan이다. JAR/manifest hash와 선택한 컨테이너의 ID·image·Compose project·main-service label·running metadata만 확인한다. plan은 컨테이너 staging/exec, 사용 기록, SDK 호출을 하지 않는다. plan의 `runtimeEnvironmentUnchecked=true`는 명시적 경계이며 런타임 환경 검사를 통과한 뜻이 아니다. 실제 child는 dev profile·logging/true·고정 From/서울 region, endpoint override 없음도 검사한다. AWS_REGION 부재 시 기존 application.yml의 서울 기본값을 사용한다.

실행 모드는 별도 승인으로 고정한 manifest SHA를 요구한다. 기존의 private **호스트 영속 state directory**에서 trialId별 디렉터리를 atomic mkdir로 먼저 선점한다. 호스트 state는 container/image/volume 재생성과 독립적으로 보존해야 한다. root·상대 경로·symlink state root·canonical path 불일치·기존 claim은 차단된다. 컨테이너에도 atomic CREATE_NEW claim을 남겨 같은 컨테이너의 다른 프로세스를 차단한다. SDK 생성 실패·staging 실패·429/403·timeout/5xx·프로세스 중단·receipt 부재까지 예산을 소모하며 자동 재시도·claim 삭제·outbox 보상 작업은 없다.

이는 승인된 manifest와 운영 경로 내의 재시작/경합 제한이다. 호스트 관리자가 claim을 지우거나 trialId/state path를 바꾸는 행위까지 보안 권한으로 막는 장치는 아니다. 실패 뒤 재시도를 원하면 결과와 UNKNOWN 가능성을 보고하고 새 실행 범위를 다시 승인받는다. 기존 claim을 해제하는 명령은 제공하지 않는다.

## 로컬 검증과 후속 실행 명령

```bash
# 오프라인 단위/메일 회귀와 검토용 JAR
cd main-service
./gradlew test --tests 'io.edupilot.mail.*' bootJar --offline --no-daemon
cd ..
node --test scripts/qa/mail-trial/run-once.test.mjs

# 기존 권한의 DEV 운영자가 metadata-only plan을 실행할 때. 실제 주소를 stdout에 출력하지 않는다.
bash scripts/qa/mail-trial/run-once.sh plan PRIVATE_MANIFEST REVIEWED_BOOT_JAR

# 별도로 승인된 identity 조회: STS 최대 1회, SES 0건. 이번 준비에서는 실행하지 않는다.
bash scripts/qa/mail-trial/run-once.sh identity PRIVATE_IDENTITY_MANIFEST REVIEWED_BOOT_JAR APPROVED_IDENTITY_MANIFEST_SHA256

# 아래는 별도 실행 승인 후의 경로이며 이번 준비에서는 실행하지 않는다.
# 승인에는 existing host state 경로, public JAR staging 1개, child 실행 1회도 포함한다.
bash scripts/qa/mail-trial/run-once.sh execute PRIVATE_MANIFEST REVIEWED_BOOT_JAR APPROVED_MANIFEST_SHA256
```

운영 Linux 호스트의 bash/sha256sum/Docker와 컨테이너의 Java 21을 사용한다. 설치·image pull·Compose 재생성을 하지 않는다. 검토된 JAR은 기존 UID/GID 10001이 읽을 수 있는 공개 코드 artifact이며 비공개 manifest는 stdin으로만 보낸다. host staging은 검토 wrapper/JAR 각 1개와 private manifest 1개이며, 기존 운영자 소유의 검증된 영속 경로 1개를 없을 때만 0700으로 만드는 범위를 승인에 포함한다. sudo/chown/그룹 추가·기존 경로 권한 변경 없이 현재 운영자 권한을 사용한다. container staging은 `/tmp/uteum-ses-trial-TRIAL_ID.jar` 한 개이고 해당 모드 claim도 `/tmp`에 남는다. 실제 대상·owner/canonical path·파일시스템을 먼저 readback하여 고정하며 자동 보정하지 않는다. local wrapper 테스트는 가짜 Docker, Java 시험은 Mockito/합성 데이터만 사용한다. native bootJar의 plan/미승인 입력으로 진입을 확인하며 네트워크나 Spring 컨텍스트를 시작하지 않는다.

## 다음 승인 질문의 정확한 범위

현재 미확인 AWS 계정/DEV 실행 identity 증거·full container/image ID·host state 경로를 확인하고, 최종 source/JAR/manifest hash와 함께 사용자에게 다음을 묻는다.

> 확인한 AWS 계정의 현재 DEV main 컨테이너에서 기존 credential chain을 사용해, 서울(ap-northeast-2) no-reply@uteum.com → 확정한 APPROVED_INBOX_1 한 주소로 **추가 합성 TEST 메일 최대 1건**을 보내는 별도 child 시험을 승인하는가? 계정 생성·변경 0건이며 IAM·provider·outbox·배포 전환 없이 검토된 JAR 1개 staging과 영속 사용 기록을 포함한다. 결과 불명/실패도 재시도하지 않는다.

2026-10-05 [SES 공식 가격](https://aws.amazon.com/ses/pricing/) 기준 기본 outbound 추가 1건은 à-la-carte $0.00010, Essentials/Pro/Enterprise 첫 구간은 $0.00016/$0.00022/$0.00023이다. 현재 plan/add-on·세금/환율·host 전송은 미확인이므로 이 계산은 기본 outbound 추가분이며 전체 비용 상한 확정이 아니다. 새 요금제/IP/기능을 켜지 않는다. 앞선 콘솔 1건과 별개로 **앱 추가 최대 1건**, 현재 준비 발송 0건이다.

CloudWatch 보존기간 메타데이터 확인은 [별도 조회 계획](dev-log-metadata-plan.md)으로 연결한다. 유료 AI 대안 검토·호출은 이 범위에 포함하지 않는다.
