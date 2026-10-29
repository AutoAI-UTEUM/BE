# DEV 전환·복구 증빙 인계 — 2026-10-08

이 문서는 PR541 code candidate `58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e`의 전환·복구 조건을 한 운영 원장으로 준비하는 **미실행 검토안**이다. 실제 서버 조회·설정·backup/restore·DB·메일·배포를 수행하지 않았다. 정책/FE 선행 조건과 해당 DEV 전환의 별도 실행 승인이 필요하다. [정책 검토표](../policies/launch-review-20261008.md), [FE 증빙](fe-auth-contract/FE-READINESS-20261008.md), [중단 경로 검토](mail-trial-interruption-review-20261008.md)를 함께 본다.

## 이미 있는 근거와 새 운영 증거

| 근거 | 보존한 결과 | 새 운영창에서 별도로 입증할 범위 |
| --- | --- | --- |
| PR541 exact-head Main/AI CI | [Main37595588035](https://github.com/AutoAI-UTEUM/BE/actions/runs/37595588035)·[AI37595588034](https://github.com/AutoAI-UTEUM/BE/actions/runs/37595588034) 필수 실제 단계 SUCCESS | 승인된 release/merge SHA·CI, build/source manifest·현재 container image/RepoDigest·health/readiness 일치 |
| 원격 develop read | 2026-10-08에도 BE `b5f659bf223daff2a4cbda7b36c2fbae543494e7`, FE `f1ad9d924b4768f798dc438066a311a2aac955f0` | Git ref는 현재 실행 image 증거가 아님. 대상 DEV·Compose/DB/worker를 운영자가 비공개 대조 |
| 10-07 사용자 DEV 읽기 | 07:09:40 UTC 당시 image b5, Flyway53/failed0, outbox/reservation 부재, SENT TEST2/attempt합2 | 과거 snapshot을 새 quota·inflight0 또는 resolved Spring 설정으로 재사용하지 않음. 배포 후 pending V54~63·schema·legacy reservation 대조 |
| 사용자 로그·ADMIN 완료 | [운영 인수 기록](operations-acceptance-20261007.md)의 DEV 로그 각14일·지정 계정 승격/readback | 재조회·재승격 요청 없음. 실제 검토 결정 시 현재 권한 재검사와 전체 reviewer binding은 별도 |
| 기존 메일 승인 | 서비스4건/각 신규1-ID·승인 주소/성인LOCAL1·사용자 직접 password; 기존 SDK1/TEST1 소진 | 운영 제어/전환 승인을 예산 승인과 구분. 현재 서비스 사용0/4, 새 identity·TEST·대체ID/retry 예산 없음 |

현재 source는 V1~53을 보존하고 V54~63 열 개를 추가한다. PR541은 migration·DEV deploy workflow·메일 암호화 경로를 바꾸지 않았다. 새 문서 작업도 runtime/migration/config를 바꾸지 않는다. 실제 Flyway 정수 checksum은 운영 DB validation과 검토 source의 Flyway 계산값으로 확인하며 파일 SHA-256으로 대신하지 않는다.

## 복구 지점 확인의 최소 범위

운영자가 승인된 DEV application DB와 **같은** 대상의 private 복구 지점, 이전 immutable image 및 Compose/Nginx/private 설정 복구 참조를 준비한다. 새 backup이 필요하면 별도 승인된 DEV 범위 안에서 기존 허용 권한을 사용하며 기존 backup/원본을 삭제하지 않는다. root fallback·권한 확장·production backup·새 cloud upload/retention 삭제를 추가하지 않는다. 기존 `scripts/db-backup.sh`는 prod/S3/root/retention 동작이 있어 이 계획의 DEV 준비 명령으로 그대로 실행하지 않는다.

증거는 대상 alias·UTC·DB/MySQL 계열·source/Flyway version, 생성 exit·압축 검사·checksum·보호된 보관 및 책임자 참조, 기존 동일 계열 복원 경로의 근거만 받는다. private path·DB row·원문·secret/key/전체 env·backup 내용은 공유하지 않는다. 압축/파일 hash 확인은 실제 복원 성공이나 복원 예행 증거가 아니다. 실제 DB 복원 실행은 현재 준비 범위에 포함하지 않는다.

이메일/DOB/TEAM 데이터를 만든 뒤 과거 b5 image로 단순 복귀하면 새 제한·enum reader·삭제 hook을 잃을 수 있다. [TEAM 복구 경계](../launch-team-operational-readiness.md)의 gate/reader/epoch·현재 삭제 원장과 withdrawal cleanup을 보존하는 검토된 roll-forward 또는 차단 상태를 사용한다. 자동 V53 restore·Flyway repair/ignore/outOfOrder/down migration·schema DROP을 준비 완료로 간주하지 않는다. 복원이 필요하면 이후 데이터·발송/quota·삭제 원장 영향까지 별도 검토·승인한다.

## 비민감 운영 인계 양식

`null`과 `NOT_ATTESTED`는 아직 조회/승인되지 않은 값이다. 아래는 메타데이터 원장 구조이며 새 권한·자동 검증·실행을 만드는 스크립트가 아니다. 실제 정책/운영값은 이 양식을 쓴 이유만으로 채우지 않는다. 원문 대신 비공개 보관 기록을 찾을 수 있는 alias/receipt 참조를 사용한다.

```json
{
  "schema": "dev-launch-recovery-handoff-20261008-v1",
  "status": "DRAFT_NOT_AUTHORIZED",
  "environment": "DEV",
  "codeCandidateSha": "58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e",
  "codeCandidateTree": "8404485ab66793764212d05da739bc66b81fc12e",
  "release": {
    "reviewedSha": null, "reviewedTree": null,
    "requiredCiRefs": [], "executionApprovalRef": null
  },
  "prerequisites": {
    "effectiveReviewedPoliciesRef": null, "feReadinessReceiptRef": null,
    "normalUiReceiptRef": null, "approvedOperatorRef": null
  },
  "privateRecovery": {
    "targetAlias": null, "createdAtUtc": null, "sourceFlywayVersion": null,
    "mysqlFamily": null, "privateReceiptRef": null,
    "creationExit": null, "compressionCheck": null, "checksumCheck": null,
    "restrictedStorageConfirmed": null, "responsibleOperatorRef": null,
    "previousImageConfigReceiptRef": null, "existingRestorePathReceiptRef": null,
    "liveRestorePerformed": false
  },
  "postDeployAttestation": {
    "observedAtUtc": null, "targetAlias": null, "imageSourceSha": null,
    "imageRepoDigest": null, "healthReadinessReceiptRef": null,
    "flywayVersion": null, "failedMigrations": null,
    "checksumMatchesReviewed": null, "legacyQuotaReceiptRef": null,
    "actualBoundSpringReceiptRef": null, "allWorkersReceiptRef": null,
    "unmodifiedPrivateKeyBindingConfirmed": null, "quotaInflightUnknownReceiptRef": null
  },
  "mailPlan": {
    "serviceAttemptsMaximum": 4, "serviceAttemptsUsed": 0,
    "perTrialNewDeliveryIds": 1, "perIdAttemptsMaximum": 1,
    "betweenTrialsProvider": "logging", "betweenTrialsMode": "PAUSED",
    "finalProvider": "logging", "finalMode": "PAUSED",
    "nominalMainRecreations": 9, "sixRecreationAlternativeAdopted": false
  },
  "executionAuthorized": false,
  "thisPreparation": {
    "sshSessions": 0, "dbSessions": 0, "settingsChanges": 0,
    "backups": 0, "restores": 0, "deployments": 0,
    "mailProviderCalls": 0, "paidAiCalls": 0
  }
}
```

## 전환 시 중단 조건과 결과 구분

정책·FE·복구 증거·실제 변경 승인 중 하나라도 없으면 배포/가입/서비스 메일을 HOLD한다. 진행이 승인돼도 binding 누락·다른 worker 제어·legacy 관계/쿼터 불일치·inflight/UNKNOWN·만료·guard 활성·artifact 불일치면 PAUSED로 중단한다. 이미 시작된 provider 호출을 취소됐다고 가정하지 않고, quota/attempt 초기화·대체ID·과거 TEST 재전송·가입 guard 해제는 하지 않는다.

develop 전환은 main/AI image build·전체 Compose pull/up·Nginx restart와 경우에 따라 mutable MySQL/Nginx 재생성을 수반한다. 현재 시험 조건을 보존하면 초기 배포1+진입4+PAUSED복귀4=9회다. 분 단위 중단 시간을 약속하지 않으며, 독립 TEST CLI를 서비스 outbox 인수로 대체하지 않는다. 6회 정적 대안은 별도 조건/검증/승인 대상이고 이 양식은 채택하지 않았다.

준비 문서/형식 확인, source/CI, 합성 검사, 실제 container/DB 설정, provider 수락/SENT, SMTP 수신, inbox 확인, token/UI 인수, 실제 사본 파기·복구는 각각 다른 결과다. 현재 미실행 항목을0건 정상/PASS로 채우지 않는다.
