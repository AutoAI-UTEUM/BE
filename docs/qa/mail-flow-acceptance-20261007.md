# 메일 3종·fragment·구형 outbox 합성 인수 (2026-10-07)

## 기준과 변경 범위

- 기준: [PR533](https://github.com/AutoAI-UTEUM/BE/pull/533) exact `28e265397dc52667fd5e7f98f1e782ba2ddafef7`.
- 브랜치: `feature/mail-flow-acceptance-20261007`. 원본 checkout과 기존 사용자 변경을 보존한다.
- 전용 신규 `MailFlowAcceptance*` 테스트와 이 문서, 재현 후 명시적으로 소유권을 조정한 `PasswordResetService.java` 및 `PasswordResetServiceTest.java`의 최소 수정만 포함한다.
- 다른 runtime, API/query 계약, migration, 설정, FE, PR525 격리 발송과 PR532 통합 구현은 변경하지 않는다.
- 원요청: `1555840753727447183`, `1556291186292162600`, `1556524347882086413`. Related to #471, #473, #479.

| 단계 | 이번 작업 |
| --- | --- |
| 코드 구현 | 전용 회귀 10개와 reset token/outbox의 동일 expiry 전달 최소 수정 |
| 합성 검사 | 전용 H2 메모리 DB, mail/AI mock, `mail-flow-*@example.test` 등 합성 주소, 문서용 `192.0.2.*` IP와 합성 키 |
| DEV 실인수 | 미실행. 기존 SDK TEST 단건 수신은 세 종류 서비스 메일·FE 링크 인수와 구분 |
| PROD | 배포·실계정·실메일·운영 outbox·권한 변경 미실행 |

## 기존 구현과 새 회귀 대조

| 경로 | 기존 근거 | 신규 검증 |
| --- | --- | --- |
| 가입→확인→reset→탈퇴 3종 | `ServiceMailDispatchLifecycleJpaTest`: 실제 API, 동의, 이메일 업무 gate, reset, 탈퇴, 승인 ID 3개와 비시험 backlog 격리 | 정상 전체 흐름을 복제하지 않고 상태 경계를 추가 |
| LOCAL/Google fragment 발급·재발급 | `EmailVerificationFragmentContractTest`: origin, text/HTML 같은 fragment, hash, 30분 만료 | 첫 메일이 READY인 동안 재발급, 두 payload 회수와 최신 token만 확정 |
| fragment 회수·1회 사용 | `EmailVerificationFragmentOutboxJpaTest` | 정확한 만료 일치와 만료 직전 claim 후 provider 직전 차단 |
| 구형 outbox | `EmailOutboxMigrationTest`, `EmailVerificationFragmentOutboxJpaTest`, `EmailOutboxRecoveryIsolationTest` | 미만료 query 그대로 회수, 정확히 만료한 query 제거·quota 미예약 |
| reset 재발급·만료 | `PasswordResetApiIntegrationTest`, `PasswordResetServiceTest` | 이전 token 거부, 최신 reset 1회 사용, reset과 이메일 확인 독립, 저장 지연 만료 회귀 |
| 탈퇴 완료 메일 | `ServiceMailDispatchLifecycleJpaTest` | 원래 수신자 유지, 24시간 payload 만료, 기존 확인/reset token 거부와 잔존 대기 작업 |

`MailFlowAcceptanceJpaTest`의 8개 시나리오:

1. 첫 fragment가 READY인 동안 재발급해도 기존 본문을 바꾸지 않는다. 두 메일이 회수될 수 있고 최신 링크만 확인에 성공한다. 재사용은 거부하고 VERIFIED 재요청은 작업을 추가하지 않는다.
2. 가입 token과 outbox가 같은 30분 경계에서 만료한다. confirm은 `EMAIL_VERIFICATION_TOKEN_INVALID`, payload는 `FAILED/PAYLOAD_EXPIRED`, provider 시도는 0이다.
3. 만료 1초 전 claim도 만료 시각의 `beginSending`에서 차단하고 본문을 제거한다.
4. 미만료 구형 `/verify-email?token=` text/HTML은 변경 없이 mock sender로 전달된다. 계정은 확인되지 않는다.
5. 정확히 만료한 구형 query는 제거하고 발송·quota 예약을 하지 않는다.
6. reset 재발급은 이전 token을 무효화한다. 최신 reset은 1회 사용하며 비밀번호만 바꾸고 가입 이메일 확인은 여전히 필요하다.
7. 저장 지연 없는 reset은 token/outbox가 같은 경계에서 거부되고 기존 비밀번호를 유지한다.
8. 탈퇴 후 확인/reset token은 거부한다. 탈퇴 완료 메일은 익명화 전 원래 수신자에 연결하고 token을 포함하지 않는다. 미만료 기존 payload는 worker가 회수할 수 있다는 현재 동작도 확인한다.

NORMAL 모드는 **mock provider를 사용하는 H2 내부 검사**다. 실제 NORMAL 전환이나 외부 발송 권한이 아니다. 복호화는 합성 fixture에만 적용하고 운영 본문은 조회하지 않는다.

## 저장 지연의 reset 결함과 최소 수정

`MailFlowAcceptanceResetDeadlineTest`는 실제 `EmailService`와 실제 issuer 계산, mock repository/worker를 사용한다. token 저장 도중 clock을 90초 전진시키며 외부 provider는 호출하지 않는다.

| 항목 | 시각 |
| --- | --- |
| 발급 | `2026-10-07T12:00:00Z` |
| token 저장 완료 | `12:01:30Z` |
| 저장 token 만료 | `12:30:00Z` |
| 수정 전 reset outbox 만료 | `12:31:30Z`, token보다 90초 늦음 |
| 수정 후 전달값 | token과 같은 `12:30:00Z` |

확인 issuer는 정확한 token 만료를 `sendAsync(message, expiry)`로 전달한다. 수정 전 reset issuer는 token의 발급 시각+30분을 저장하고 `sendAsync(message)`를 호출해 메일 요청 시각+30분을 다시 계산했다. 따라서 confirm이 만료를 거부하는 동안 payload는 발송 가능할 수 있었다. 짧은 저장 지연도 같은 차이를 만든다.

`PasswordResetService.request`에서 아래 만료값을 한 번 계산해 두 곳에 전달하도록 수정했다. query 링크, 요청 실패 허용, 한도와 트랜잭션 정책은 유지한다.

```java
Instant expiresAt = now.plus(Duration.ofMinutes(EXPIRY_MINUTES));
tokenRepository.saveAndFlush(PasswordResetToken.create(
    user, hash(rawToken), expiresAt, ip, now
));
emailService.sendAsync(emailTemplates.passwordReset(
    "/reset-password?token=" + rawToken, EXPIRY_MINUTES
).to(user.getEmail()), expiresAt);
```

`resetQueueMustKeepTheTokenDeadlineAfterNinetySecondsOfPersistenceDelay`는 동일 만료를 요구하는 활성 회귀다. 실패를 비활성화하거나 만료 차이를 성공 조건으로 고정하지 않는다. 기존 `PasswordResetServiceTest`도 고정 clock+30분의 정확한 expiry를 새 2인자 stub/verify에 요구한다. 실패 허용·없는 계정의 동일 BCrypt 작업·발송 없음 검증은 보존한다.

reset의 `/reset-password?token=`은 [현재 API 계약](../api-spec.md)이다. 가입 확인의 `/verify-email#token=`과 혼동해 변경하지 않는다. reset의 fragment 전환을 원한다면 별도 FE/BE 계약과 인수가 필요하다.

## 구형 작업과 남는 운영 결정

[기존 outbox 정책](../mail-outbox.md)과 [운영 준비](../launch-operational-readiness.md)의 현재 동작:

- V54 이전 본문 없는 QUEUED는 `FAILED/LEGACY_PAYLOAD_UNAVAILABLE`이다. SENT/FAILED/RATE_LIMITED와 과거 quota 예약은 보존하고 재발송 성공으로 표시하지 않는다.
- V54 이후 미만료 READY/RETRY는 저장 당시 text/HTML을 보존한다. 새 fragment 코드가 구 query를 자동 변환하지 않는다. provider는 생성 시 고정되지 않아 전환하면 과거 작업도 현재 provider로 발송될 수 있다.
- 재발급·탈퇴로 token이 무효화돼도 이전 payload는 즉시 취소되지 않는다. confirm은 token 유효성을, outbox는 payload 만료를 검사한다. 오래된 링크 안내와 불필요한 발송 처리 방침은 운영자가 정해야 한다.
- 만료 본문은 제거한다. SENDING 결과 불명은 UNKNOWN으로 수렴해 자동 재전송하지 않는다. Logging SENT도 SES로 자동 재발송하지 않는다.
- type/시각 metadata만으로 운영 payload의 링크 형식을 확정할 수 없다. 승인된 집계·secret 관리 버전 연속성과 운영자 확인을 사용하고 본문/주소/token/키를 덤프하지 않는다. 행 삭제·attempt 초기화·직접 VERIFIED 변경으로 시험 조건을 만들지 않는다.

## 허용된 합성 수신과 실제 전환 조건

**현재 허용 범위:** H2 내부 `EmailSender` mock의 합성 수신만이다. SDK identity/TEST 단건 예산은 소진됐고 이번 작업의 실제 서비스 메일 수신·발송은 0회다. SES API, simulator, 실제 mailbox와 개발/운영 outbox를 호출·변경하지 않는다. 기존 `APPROVED_INBOX_1`은 이전 비공개 승인 handle이며 새 서비스 인수나 보호자 회신 주소의 To 승인이 아니다.

| 후속 조건 | 정확히 필요한 근거 | 이번 작업의 한계 |
| --- | --- | --- |
| 후보/CI/artifact | 최종 source SHA의 Main/AI 필수 CI, 동일 image/Boot JAR source 근거 | CI는 코드 검증이고 DEV 배포 artifact 확인과 구분 |
| 배포/FE | DEV/Flyway, FE source/build/served artifact, fragment 메모리 이동·주소 정리·명시 POST, 구 query 재발급 안내 | 실제 FE/DEV 인수 미실행 |
| 현재 발송 설정 | 모든 인스턴스 enabled/provider/From/region/base URL/dispatch와 credential 경로 | 과거 SDK 수신이 현재 후보 설정을 증명하지 않음 |
| SES/주소 | 현재 앱 역할 SendEmail, 적용 identity/From, 실제 수신자 한 명의 새 명시 승인 | 권한·계정 변경·추가 SDK 조회/TEST 없음 |
| 대기/quota | READY/RETRY/CLAIMED/SENDING/UNKNOWN·만료·quota 집계, 구 payload 방침, 진행 중 호출·다른 producer 격리 | 실제 집계/처리 결정 미실행 |
| 키 연속성 | 기존 encryption key 또는 JWT-HKDF secret 관리 버전 일치 | 값/hash 추출 없이 운영자 증거 필요 |
| 발송 예산 | 고정 DB·ID 1~3개·수신자 한 명, 모든 worker 동일 ISOLATED_TRIAL, 이전 시도 0 | 신규 서비스 발송 예산 승인 없음 |
| 수신 인수 | 202/QUEUED, provider 수락/SENT, 실제 inbox 시각을 구분하고 명시 confirm·재사용/만료 거부 확인 | 실제 서비스 수신 0회 |

| 별도 승인을 받을 시나리오 | 최소 새 서비스 발송 |
| --- | --- |
| 가입 확인과 동일 링크 1회 확정/재사용 거부 | 1건 |
| 가입 발급 후 재발급, 이전 거부와 최신 확정 | 2건 |
| 가입 확인·reset·탈퇴 완료 각각 1회 | 3건 |
| 3종 전체와 가입 재발급 추가 | 4건, 단일 ISOLATED_TRIAL의 1~3개 범위 초과 |

표는 실행 승인이나 설정 변경 명령이 아니다. 이미 사용한 SDK 예산이나 승인 ID 변경/초기화로 4건 계획을 우회하지 않는다. 4건 이상은 별도 예산·실행 단위·승인 범위를 정해야 한다. 만료/재사용 거부는 받은 링크로 추가 발송 없이 확인할 수 있다. 승인된 합성 성인 계정만 사용하고 학생/실계정·외부 AI·보호자 승인 조작을 포함하지 않는다.

## 검사 결과와 재현 명령

**수정 전 신규 집중검사:** 10개, PASS 9 / FAIL 1 / ERROR 0 / SKIP 0, Gradle exit 1. `MailFlowAcceptanceJpaTest` 8/8 PASS, deadline suite는 확인 지연 PASS·reset 지연 FAIL이었다. 실패 expected는 `12:30:00Z`, actual은 `12:31:30Z`였다.

**기존 집중 대조:** 아래 6개 suite 20/20 PASS, FAIL/ERROR/SKIP 0, Gradle exit 0. 이 결과는 수정 전 컴파일 artifact에서 나온 결과이며 수정 후 결과로 재집계하지 않는다.

| suite | PASS |
| --- | --- |
| `EmailVerificationFragmentContractTest` | 6 |
| `EmailVerificationFragmentOutboxJpaTest` | 2 |
| `EmailOutboxDispatchIsolationJpaTest` | 6 |
| `EmailOutboxMigrationTest` | 1 |
| `EmailOutboxRecoveryIsolationTest` | 4 |
| `ServiceMailDispatchLifecycleJpaTest` | 1 |

전용 Gradle user home과 기존 dependency cache의 읽기 복사, `--offline --no-daemon --max-workers=1`을 사용했다. 초기 캐시의 STS 누락과 JDK ZipFS 샌드박스 접근 오류는 전용 캐시 복사·승인된 집중검사 실행으로 해결했으며 ACL/계정 권한을 변경하지 않았다. 로컬 전체 Gradle build/check와 MySQL 새 인스턴스는 실행하지 않았다.

수정 후 로컬 재검증 준비 중 실행/파일 도구 연결이 끊겼다. 따라서 로컬 수정 후 PASS를 주장하지 않는다. GitHub 연결로 동일 feature 변경을 게시하고 **최종 head의 기존 Main/AI CI**를 확인하며 run/head/실제 단계 결과는 PR 본문에 기록한다. CI는 합성 코드 검사이고 실서비스 실행이 아니다.

```powershell
# main-service; MAIL_GRADLE_HOME은 이 세션 전용 캐시
gradle --gradle-user-home $MAIL_GRADLE_HOME --offline --no-daemon --max-workers=1 test `
  --tests '*MailFlowAcceptance*' `
  --tests '*ServiceMailDispatchLifecycleJpaTest' `
  --tests '*EmailVerificationFragmentContractTest' `
  --tests '*EmailVerificationFragmentOutboxJpaTest' `
  --tests '*EmailOutboxMigrationTest' `
  --tests '*EmailOutboxDispatchIsolationJpaTest' `
  --tests '*EmailOutboxRecoveryIsolationTest' `
  --tests '*PasswordResetServiceTest' `
  --tests '*PasswordResetApiIntegrationTest' `
  --tests '*EmailServiceTest'
```

DEV 서비스 수신·FE 실인수·PROD·운영 backup/restore는 미실행이다. develop/main merge와 배포를 하지 않는다.
