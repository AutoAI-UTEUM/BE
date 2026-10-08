# 운영 인수 6묶음과 읽기 전용 metadata 준비 (2026-10-07)

브랜치: `feature/operations-acceptance-20261007`. Discord 메시지 `1555841308650770496`의 6묶음은 부모가 전달한 범위를 기준으로 한다. 이 작업에서 Discord를 읽거나 답장을 보내지 않았다.

기준은 [PR533](https://github.com/AutoAI-UTEUM/BE/pull/533) exact `28e265397dc52667fd5e7f98f1e782ba2ddafef7`다. 런타임 통합 기준 [PR532](https://github.com/AutoAI-UTEUM/BE/pull/532)는 `3343e608fa80dd6bdb5888454ad6fac5b09461ad`이며 PR533은 그 위의 운영 준비 변경이다. 아래 자료는 C 전용 신규 문서·검사다. 공통 runtime·migration·workflow·기존 테스트를 수정하지 않는다. 저장 동시성 검사는 A, 메일 검사는 B가 소유한다.

## 이미 확인된 이력

| 이력 | 근거와 유지할 상태 | 이력으로 대신할 수 없는 현재 확인 |
| --- | --- | --- |
| DEV main/ai 로그 | 부모가 전달한 사용자 실행 결과 `USER_REPORTED_READ_COMPLETE`, **2026-10-06 17:39:09 UTC**. 두 service 모두 awslogs, `/edupilot/dev/main`·`/edupilot/dev/ai` 정확 일치 각각 1개, **보존 14일**. 원문 읽기·설정 변경·추가 agent 조회 0 | 현재 image/Flyway/flags, 모든 이벤트의 실제 삭제 시각, PROD 보존 설정 |
| 지정 검토 계정의 관리자 승격 | 사용자 실행 결과 `USER_REPORTED_PROMOTED_VERIFIED`, **2026-10-06 13:31 UTC**(분 단위 보고). 지정 계정 **ACTIVE ADMIN 승격·정상 API readback 완료**. 공개 참조는 `GUARDIAN_REVIEW_ACCOUNT` | 현재 모든 인스턴스의 reviewer binding·정책 활성화·FE TEAM 지원 |

커밋 밖 사용자 실행 근거의 공개 참조는 `DEV_LOG_RETENTION_USER_RECEIPT_20261006`와 `GUARDIAN_ADMIN_USER_RECEIPT_20261006`이다. 부모가 보존한 비공개 기록과 대조했다. 실제 계정 주소·ID·ARN·container ID·키/키 hash·원문은 새 공개 자료에 복사하지 않는다. 두 완료 이력을 미확인으로 되돌리거나 승격/로그 확인을 재실행하는 승인을 추론하지 않는다. [과거 로그 조회 계획](../dev-log-metadata-plan.md)과 [PR533 당시 계정 미확인 문구](../launch-team-operational-readiness.md)는 이 사용자 실행 이전의 준비 상태다.

## 6묶음 매핑

전체 파일·SHA-256·과거 검사 receipt digest는 [evidence-map.json](../../tools/qa/operations-acceptance-20261007/evidence-map.json)에 고정했다. **기존 참조 46개 + V1–V63 migration 63개**를 기준 SHA의 Git blob과 직접 대조했다. text source hash는 Git blob 기준이며 로컬 재검사는 Windows checkout의 CRLF만 LF로 정규화한다. 백업/실제 artifact digest에는 이 정규화를 적용하지 않는다. 이 대조는 테스트 본문 실행이나 실서버 인수 완료를 뜻하지 않는다.

| 묶음 / manifest ID | 기존 구현·검사 근거 | 남은 실제 인수와 수집할 최소 metadata |
| --- | --- | --- |
| 권한 격리·정답 노출 / `access_answers` | [SessionAccessRevocationJpaTest](../../main-service/src/test/java/io/edupilot/session/SessionAccessRevocationJpaTest.java), [SessionStreamAccessDeliveryTest](../../main-service/src/test/java/io/edupilot/session/SessionStreamAccessDeliveryTest.java), [TurnCompletionAccessJpaTest](../../main-service/src/test/java/io/edupilot/session/TurnCompletionAccessJpaTest.java), [QuizApiContractTest](../../main-service/src/test/java/io/edupilot/quiz/QuizApiContractTest.java), AdminSecurityIntegrationTest. 제출 전 public DTO, 다른 소유자·멤버 제거·자료 연결 해제·정지/역할 변경·SSE 전달 경계 | 승인된 합성 계정의 FE/proxy 인수 receipt. 허용/거절 집계·정답 필드 노출 건수·source/artifact SHA만 공유. 실제 계정 생성·역할 변경·소유권 변경은 이번 범위에 없음 |
| 저장 동시성·중복 / `persistence_duplicates` | [ExamFailureSecurityJpaTest](../../main-service/src/test/java/io/edupilot/exam/ExamFailureSecurityJpaTest.java)의 `concurrentSubmissionsPersistOneAttemptAndOneFixedAnswerSet`, ExamGradingRecoveryJpaTest의 복구/worker 경합, QuizSubmissionReconstructionTest, NoteIdempotencyMigrationTest, TurnUsageSecurityJpaTest. [기존 회귀 설명](../release-runtime-regressions.md) | **A 결과를 연결**한다. C는 저장 테스트를 추가/수정/실행하지 않는다. attempt·답안 집합·AI 실행·중복 건수의 합성 검사 receipt와 source SHA만 받는다. 개별 답안/requestId 원문 금지 |
| PDF worker 장애·파일 정리 / `pdf_worker_cleanup` | [test_pdf_security.py](../../ai-service/tests/test_pdf_security.py), [test_pdf_worker_lifecycle.py](../../ai-service/tests/test_pdf_worker_lifecycle.py): 큐/실행 상한, timeout/crash/disconnect, child reap, 임시 파일 소유권·다른 worker 격리. MaterialFailureJpaTest·DeletionJournalJpaTest·DeletionStorageTest·DeletionWorkerTest | 현재 worker/lease·stale·due/failed/policy-pending·임시/고아 파일 **집계**. 실제 JVM kill·worker 장애 주입·파일 삭제·외부 AI 정리는 별도 승인 후의 인수. 로컬 새 객체/합성 process 검사를 실제 컨테이너 재시작으로 표시하지 않음 |
| 요청 제한·보안·내부 AI 인증 / `limits_security_internal_ai` | [test_auth.py](../../ai-service/tests/test_auth.py)의 missing/mismatched token 거절, ExamDraftRateLimiterTest·NoteImportRateLimiterTest·AdminSecurityIntegrationTest. [Nginx](../../infra/nginx/edupilot.conf)의 AI 60/min·API 600/min·429, 숨김 경로·SSE proxy. SecurityConfig | 적용된 Nginx hash·typed limit·직접 공개 port 수·CORS 검토 상태·내부 token **존재/비공개 binding 일치 boolean만**. token/JWT/키 hash/전체 env 금지. 실제 429 부하·인증 거절 요청은 현재 조회 계획으로 수행하지 않음. 메일 제한/발송은 B 근거 참조 |
| image·migration·flags·백업/새 호스트 복구/rollback / `images_migrations_restore` | MigrationVersionUniquenessTest·GuardianTeamFullMigrationTest, V1–V63 파일 hash, [Compose](../../docker-compose.prod.yml), [현재 TEAM 준비](../launch-team-operational-readiness.md), [복구 runbook](../runbook-db-restore.md), 삭제 원장 restore-replay 합성 검사 | 현재 image ID/RepoDigest SHA·source revision·Compose/Nginx/FE artifact hash·Flyway 정수 checksum/success·flags의 모든 인스턴스 일치. 기존 백업 시각/크기/hash/검사 상태와 최신 trusted deletion-journal receipt. **현재 백업 생성, 새 호스트 복구, 삭제 재적용, rollback은 미실행**. 과거 복원 기록을 V60–V63 reader/hook 보존 인수로 대체하지 않음 |
| 진도·점수·사용량/비용 대사·quota / `progress_scores_usage_quota` | LearningProgressServiceTest·ReportSnapshotCalculatorTest·ExamSubmissionScoreCalculatorTest, AiQuotaServiceTest·TurnUsageSecurityJpaTest·AiUsageTransactionIntegrationTest, [test_usage.py](../../ai-service/tests/test_usage.py), [test_usage_cost.py](../../ai-service/tests/test_usage_cost.py). distinct-page 진도, 점수 범위, 실패 비용·실행별 사용량·unknown/0 구분 | 같은 UTC 기간/feature 범위의 DB 집계와 **기존** provider ledger receipt·진도/점수 대사 건수·KST 경계 인수. provider sync·새 유료 호출 없음. 비용 단위는 **1 USD = 10,000,000,000 `cost_usd_ticks`**. null 비용/토큰은 0으로 간주하지 않음 |

과거 합성 검사 receipt는 보조 근거다. `479-runtime-mysql-results.json`은 50 pass/fail0/skip0, `479-sse-revocation-mysql-results.json`은 28 pass/fail0/skip0이다. `launch-be-candidate-retention-mysql-results.json`은 **과거 source `de92a8eb84b35b65e481c52ee4038749f1be54ae`**, 18 suites/181 pass/fail0/error0/skip0이다. 서로 겹치는 suite가 있으므로 숫자를 합쳐 전체 coverage처럼 보고하지 않는다. 이번 C가 재실행한 결과도 아니다.

PR533 exact SHA의 기존 [Main CI](https://github.com/AutoAI-UTEUM/BE/actions/runs/37442834629)·[AI CI](https://github.com/AutoAI-UTEUM/BE/actions/runs/37442834527)는 성공했고 당시 AI pytest 1067 pass, metadata 82 pass였다. 이번 C의 CI/로컬 결과와 구분한다. 문서/tools 전용 PR의 기존 Main workflow는 runtime build를 조건부 생략할 수 있으며, 그런 성공을 전체 Gradle 실행 성공으로 표시하지 않는다. 새 C 검사는 workflow를 수정하지 않아 **아래 명령으로 별도 실행**해야 한다.

## 결과 단계

| 단계 | C 결과 / 남은 조건 |
| --- | --- |
| 코드 구현 | 기준 runtime·기존 검사 근거를 매핑했다. 새 기능/보안 결함 수정을 주장하지 않음 |
| 합성검사 | Node 24, 새 metadata 계약/안전 경계 **70 pass, fail0, skip0**, 참조 hash 109/109 대조. MySQL/Gradle/FastAPI·외부 AI·실제 서비스 기동 없음 |
| DEV 실제 인수 | 위 로그/ADMIN **완료 이력은 유지**. 6묶음 전체 DEV 인수는 아직 완료 아님. 현재 metadata·합성 사례의 별도 승인/운영자 receipt 필요 |
| PROD | 이 C 작업의 실제 조회/인수/배포 없음. DEV 완료 이력을 PROD 증거로 자동 복사하지 않음 |

## 로컬 manifest 확인

[metadata-manifest.example.json](../../tools/qa/operations-acceptance-20261007/metadata-manifest.example.json)은 두 완료 이력만 채웠다. 현재 7개 section은 `NOT_COLLECTED`/null, 6개 인수는 `NOT_RUN`/null이다. **예제는 exit2로 차단되는 것이 정상**이다.

```bash
node --test tools/qa/operations-acceptance-20261007/check-manifest.test.mjs
node tools/qa/operations-acceptance-20261007/check-manifest.mjs tools/qa/operations-acceptance-20261007/metadata-manifest.example.json
# 운영자가 자신의 작은 로컬 사본을 작성한 뒤에만:
node tools/qa/operations-acceptance-20261007/check-manifest.mjs PRIVATE_METADATA_JSON
```

점검기는 선택 JSON과 커밋된 evidence map만 읽는다. 네트워크·SQL·child process·credential 읽기·파일 쓰기를 실행하지 않는다. 숫자·고정 enum·SHA·UTC 시각만 허용하며 알 수 없는 필드/원문을 거절한다. 오류에 입력값·알 수 없는 key·파일명·파싱 오류 원문을 출력하지 않는다. signed Flyway checksum과 SHA-256을 구분하며 큰 token/cost 합계는 10진 **문자열**로 받아 정밀도를 보존한다.

exit1은 잘못된/민감 필드/모순된 입력, exit2는 누락·불일치·미인수, exit0은 제출된 metadata와 6개 receipt의 형식/조건 충족이다. `metadataComplete`는 조회 필드의 완성, `conditionsClear`는 정적 대조 조건의 충족, `operatorReportedAcceptanceComplete`는 운영자 보고가 모두 있는 상태다. **항상 `liveAcceptanceVerified:false`, `executionAuthorized:false`**다. 완전한 합성 사본의 provenance는 `SYNTHETIC`이며 실제 인수 보고로 승격하지 않는다. 진본성·source ancestry·대상/시각·승인·새 호스트 결과는 운영자가 별도로 확인한다.

## 사용자가 실행할 읽기 전용 수집안

이 절은 **준비된 실행안**이다. C는 실행하지 않았으며 현재 실서버 접속 승인을 뜻하지 않는다. 부모가 읽기 범위를 확정한 후, 사용자가 이미 가진 조회 경로에서 지정 DEV만 선택한다. 현재 private full container ID·Compose project/service·DB 대상·인스턴스 수를 먼저 비공개 대조한다. 여러 후보/다른 환경/조회 거부면 중단하고 `DENIED`/`MISSING`/`UNAVAILABLE`로 남긴다. 권한·설정을 확대하지 않는다.

1. 커밋 밖 로컬 사본에 `environment:DEV`, `provenance:OPERATOR_REPORTED`를 유지한다. `confirmedHistory`는 수정하지 않는다. 현재 section은 성공한 조회만 `readStatus:OK`, UTC 관찰 시각, 아래 고정 data 필드로 채운다. 실패 section의 data는 null이다. 스키마와 허용 필드는 [check-manifest.mjs](../../tools/qa/operations-acceptance-20261007/check-manifest.mjs)의 `SECTION_FIELDS`를 기준으로 한다.
2. **artifacts**: 각 선택 container의 image ID/state, 선택 image의 revision label/RepoDigest에서 SHA만 읽는다. 대상/labels 비교는 private 영역에서 하고 boolean만 공유한다. source label이 없으면 tag/`latest`로 source를 추정하지 않는다. 기존 적용 Compose/Nginx와 FE source/build/served artifact hash를 대조한다. 전체 inspect·Compose config 출력·`.env` hash/원문·전체 로그는 수집하지 않는다.

   ```bash
   # 기존에 비공개 대조한 단일 DEV 대상을 사용. 실패 출력/코드를 0건으로 바꾸지 않는다.
   date -u '+%Y-%m-%dT%H:%M:%SZ'
   docker inspect --format '{{.Image}}|{{.State.Status}}' "$PRIVATE_DEV_MAIN_FULL_ID"
   docker inspect --format '{{.Image}}|{{.State.Status}}' "$PRIVATE_DEV_AI_FULL_ID"
   docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$PRIVATE_MAIN_IMAGE_ID"
   docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$PRIVATE_AI_IMAGE_ID"
   # RepoDigest는 private 선택 image만 조회하고 digest SHA 부분만 manifest로 옮긴다.
   docker image inspect --format '{{range .RepoDigests}}{{println (index (split . "@") 1)}}{{end}}' "$PRIVATE_MAIN_IMAGE_ID"
   docker image inspect --format '{{range .RepoDigests}}{{println (index (split . "@") 1)}}{{end}}' "$PRIVATE_AI_IMAGE_ID"
   ```

3. **migrations / workerCleanup / reconciliation**: [readonly-aggregates.sql](../../tools/qa/operations-acceptance-20261007/readonly-aggregates.sql)의 inventory부터 승인된 기존 읽기 connection에서 실행한다. 이후 block은 table/column 확인과 scan 비용 검토 후 **개별 선택**한다. private prepared binding의 UTC window/cutoff를 사용하고 root 계정/password를 command argument나 공개 파일에 넣지 않는다. query 실패·미적용 table은 0이 아니다. Flyway checksum은 해당 검토 파일의 **Flyway 계산값**과 비공개 대조하여 `checksumMatchesReviewed`를 표시한다. 파일 SHA-256으로 대신하지 않는다.
4. **flags / security**: 기존 effective-configuration 검토 경로에서 허용된 boolean·양의 정수만 읽는다. 내부 token은 기존 비공개 binding 일치/존재 boolean만 받는다. `reviewedNonsecretConfigSha256`은 이 허용 projection의 digest이며 secret·token·전체 env digest가 아니다. port 수는 검토된 Docker/network metadata에서 실제 host binding만 센다(ExposedPorts 개수로 대신하지 않음). worker health와 temporary/overdue/orphan 파일 수는 승인된 monitor/count-only 경로에서 받는다. 파일명·PDF 내용·학생 원문을 출력하지 않으며 경로가 없으면 미확인으로 둔다.
5. **backupRestore**: 이미 존재하는 백업과 무결성 검사 receipt의 시각/bytes/hash/상태, 최신 trusted deletion journal receipt의 시각/hash만 읽는다. 새 backup·download·restore·삭제·`Flyway repair`/schema DROP을 실행하지 않는다. 기존 새 호스트 복구/삭제 재적용/rollback receipt가 없으면 각 상태는 `NOT_RUN`이다. V60 DOB hook, V61–V63 TEAM enum reader/동의 fence를 보존하는 [현재 복구 경계](../launch-team-operational-readiness.md#v63-이후-전환복구-경계)를 먼저 검토한다.
6. **reconciliation**: UTC window를 동일하게 고정하고 KST 자정 경계를 기록한다. DB cost/token의 known 합계와 unknown 행 수를 같이 보존한다. provider 값은 기존 승인된 ledger receipt에서 같은 window/feature/model/재시도 범위로만 대조한다. null ledger·서로 다른 범위는 미대사다. SQL의 진도/점수 범위 진단은 **FE/API/DB 대사 receipt가 아니며** 그 결과만으로 `progressMismatchCount`/`scoreMismatchCount`나 PASS를 채우지 않는다.
7. **acceptance 6개**: 별도로 승인·수행한 합성 DEV 인수 결과 또는 A/B의 담당 근거가 있을 때만 status/UTC/민감 원문 없는 receipt SHA를 채운다. metadata 조회만으로 PASS를 만들지 않는다. 미실행은 `NOT_RUN`과 null, 실패/차단은 `FAIL`/`BLOCKED`와 검토된 receipt로 남긴다. 백업/복원 receipt hash는 **학생 원문/주소/키에 대한 hash가 아니다**. 로그 보존이나 ADMIN 역할을 새로 조회할 필요는 없다.

quota의 현재 구현은 `TURN`, `DOC_CHAT`, `QUIZ_ASSESSMENT`, `DIAGNOSIS` 호출부에서 사전 검사하며, KST 자정 이후 **성공·실패 usage 전체**를 센다. ADMIN exemption/disabled flag, upload·caption·grade·report fan-out 기록은 별도 경계다. 단순 전역 집계로 사용자별 잔여 quota·provider 정확 청구·동시 요청의 엄격한 비용 상한을 증명하지 않는다. [기존 quota 구현](../../main-service/src/main/java/io/edupilot/aiusage/AiQuotaService.java)과 [feature 범위](../../main-service/src/main/java/io/edupilot/aiusage/AiFeature.java)를 함께 대조한다.

실서버·계정·메일·유료 AI·운영 데이터·권한/보안 설정 변경, 백업 생성/복구/삭제, develop/main merge·배포는 이 실행안에 포함하지 않는다. feature push와 draft PR만 게시한다. 현재 C가 검증한 새 runtime 결함은 없으며, runtime 수정이 필요해지면 위치와 최소 수정안을 부모에게 먼저 전달한다.

Numeric migration checksum review (2026-10-08): the checker now compares each observed signed Flyway checksum to the pinned script catalog, in addition to the operator review flag. Catalog values were cross-checked against Flyway 12.4.0 ChecksumCalculator for all 63 versioned scripts; this does not run migrations or attest a deployed schema. LF/CRLF/CR and an initial UTF-8 BOM yield the same checksum.
