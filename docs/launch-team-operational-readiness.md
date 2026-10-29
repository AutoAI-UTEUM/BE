# PR532·V61–V63 보호자 TEAM 운영 준비 (#479, #515, #519, #526)

브랜치 한 줄: `feature/479-pr532-readiness` — #532의 TEAM_REVIEW 정책·기존 관리자·FE·메일·정리 증거를 요구하는 배포 준비 점검기와 읽기 전용 인계 절차를 보완한다.

2026-10-06 검토한 런타임 source는 [#532](https://github.com/AutoAI-UTEUM/BE/pull/532) `3343e608fa80dd6bdb5888454ad6fac5b09461ad`다. 같은 head [Main CI](https://github.com/AutoAI-UTEUM/BE/actions/runs/37435819039)와 [AI CI](https://github.com/AutoAI-UTEUM/BE/actions/runs/37435818992)가 성공했다. 이 준비 브랜치의 후속 commit/CI와 런타임 후보 source를 별도로 기록한다. source 성공은 DEV image·migration·출시 완료를 뜻하지 않는다. 이번 준비에서 develop merge·실서버 설정·권한·발송·복원·배포는 실행하지 않는다.

첫 출시는 만14세미만을 포함하며 **업체 없는 TEAM_REVIEW**가 현재 결정이다. [BE521/V60 준비](launch-operational-readiness.md)와 [이전 배포 점검](launch-deployment-preflight.md)은 당시 근거다. 보호자 관계·최종 고지·승인 후 기간이 미정이면 TEAM 설정을 켜거나 미구현 확인을 성공으로 처리하지 않는다. [현재 구현](guardian-team-review.md), [FE 계약](guardian-team-review-fe-contract.md), [관계·고지·기간 검토안](guardian-team-policy-review.md)을 함께 검토한다.

## 확정한 계정과 아직 확인하지 않은 권한

사용자는 2026-10-06 08:56 UTC에 회신함과 검토 계정으로 같은 이메일 계정을 지정했다. 실제 주소는 커밋 밖 비공개 인계 자료에 기록하며 공개 manifest는 `GUARDIAN_REPLY_INBOX`와 `GUARDIAN_REVIEW_ACCOUNT`를 사용한다. **발신자는 기존 `no-reply@uteum.com`을 유지**한다. 이 선택으로 기존 ACTIVE ADMIN 권한·계정 존재·user ID가 확인된 것은 아니다. 새로운 계정이나 권한을 만들지 않는다.

운영자는 기존 읽기 권한으로 [reviewer SELECT 템플릿](qa/launch-ops/readonly-reviewer.sql)의 `:selected_email`을 비공개 바인딩한다. 이는 prepared-statement 문법이며 mysql 셸에 그대로 붙이거나 주소를 command argument에 삽입하지 않는다. 출력은 해당 계정의 `id/role/status`와 결과 건수만 비공개 보존한다. **정확히 한 건의 ACTIVE ADMIN**이어야 그 ID를 검토된 `reviewerIds`와 대조할 수 있다. 0건·여러 건·다른 역할·정지·탈퇴·조회 실패는 BLOCKED이며 생성/승격으로 자동 보정하지 않는다. `GET /api/users/me`에는 status가 없으므로 역할 한 필드만으로 대신하지 않는다. 확인 시각과 운영 대상도 기록하고 실제 결정 시 서버의 현재 권한 재검사를 유지한다.

## 현재 manifest와 통과 의미

[예제](qa/launch-ops/readiness-manifest.example.json)와 `check-manifest.mjs`는 `launch-ops-v2-team`, #532 reviewed baseline, V60–V63 source hash를 사용한다. 이전 v1/BE521/V60 기록은 현재 후보의 완료 증거로 통과하지 않는다. 알려진 #532 CI와 소진된 SDK 시험 이력만 채웠으며, 미확인 운영값·정책·기간·권한·FE TEAM 증거·추가 발송 승인은 null/false다. 예제는 prepare/reopen 모두 BLOCKED다.

`prepare`는 검토 정책의 버전·digest·수집/목적/거부/관계/기간 참조, SERVICE와 선택 EXTERNAL_AI 구분, 선택 계정의 읽기 전용 ACTIVE ADMIN 증거, 외부 회신함·백업·정리 모니터링 계획을 요구한다. `proposedPolicy.enabled/policyConfirmed`는 **승인된 전환 계획의 후보 값**이며 현재 서버 활성화 관찰이 아니다. `reopen`은 모든 인스턴스의 실제 정책 digest·담당자 일치, V60–V63 적용/정수 checksum·실패0, FE TEAM 동작, 정리 기한 초과0과 역할/철회·AI 범위 차단 인수를 더 요구한다. 각 Flyway checksum은 검토한 파일로 Flyway가 계산한 값과 대조하며 SHA-256 파일 hash와 혼동하지 않는다.

```bash
node --test scripts/qa/launch-ops/check-manifest.test.mjs
node scripts/qa/launch-ops/check-manifest.mjs PRIVATE_MANIFEST prepare
node scripts/qa/launch-ops/check-manifest.mjs PRIVATE_MANIFEST reopen
```

점검기는 선택한 로컬 JSON만 읽고 필드명과 누락 여부만 출력한다. 네트워크·SQL·child process·자격증명·메일·배포를 실행하지 않는다. 완전한 합성 사본도 **`executionAuthorized:false`**이며 실제 근거의 진본성·freshness·승인을 검증하지 않는다. source ancestry·hash·CI·운영 snapshot의 진본성과 관찰 시각은 운영자가 별도로 대조한다. 공개 기록에 계정 주소·ID·ARN·JWT·DOB·증거 원문·키/키 hash를 넣지 않는다.

FE 읽기 기준 `f1ad9d924b4768f798dc438066a311a2aac955f0`의 기존 ON 값 `be-auth-ee69e425-v1`은 새 TEAM 지원 증거가 아니다. FE 최종 source에 신청·fragment 조회·명시적 의사 표시/현재 차수 회신·담당자 검토·범위 분리·철회/만료 처리가 연결된 증거가 필요하다. FE 저장소를 수정하거나 build flag를 대신 켜지 않는다.

## 완료된 SDK 시험과 남은 서비스 메일

2026-10-05 별도 승인으로 동일 DEV 컨테이너 환경에서 SDK identity **1회**, 합성 TEST 발송 **1건**을 실행했고 부모가 Gmail 수신과 SPF/DKIM/DMARC 확인을 전달했다. 당시 source는 `adcd10fbb91662ab1e3c5cc228b0a62552d3e9d2`, JAR SHA-256은 `9f2ac426e3a7ea02961af62ae74231d2946f9efbb07137749ab95083c22dec09`다. identity/SEND 예산은 모두 소진됐으며 재실행 승인은 없다. 이 이력은 #532 JAR identity·현재 SES 상태·Spring outbox 발송을 증명하지 않는다.

그 TEST에는 가입/재설정 토큰이 없었고 signup/reset/withdrawal 서비스 메일·fragment 명시 confirm·재사용 거절은 **미실행**이다. 별도로 범위를 승인받을 후속 첫 인수는 합성 성인 가입 EMAIL_VERIFY 1건을 제안한다. 가입·reset·탈퇴 세 종류 전체는 최소 3건, 가입 재발급까지 포함하면 최소 4건의 별도 서비스 발송 예산이 필요하다. 기존 `APPROVED_INBOX_1` 시험 수신자와 새 보호자 회신 계정은 서로 다른 참조이며 회신 주소가 시험 To로 자동 승인되지는 않는다. 현재 manifest의 plannedMessages/actions/서비스 승인은 비어 있다. 기존 READY/RETRY·UNKNOWN·Logging SENT 처리와 다른 producer 격리가 결정되기 전 전역 provider를 바꾸지 않는다.

## 운영자가 제출할 최소 읽기 전용 증거

아래는 **필요한 출력의 준비 목록**이며 실서버 조회 결과가 아니다. 승인된 기존 읽기 경로에서 지정 필드만 읽고 전체 Env/DB dump/메일 본문을 출력하지 않는다. 읽기가 막히면 unknown으로 두며 권한을 확장하지 않는다. [집계 SQL](qa/launch-ops/readonly-counts.sql)은 SELECT-only이고 inventory 후 존재하는 테이블 블록만 실행한다. 미적용 V61–V63 테이블이나 조회 실패는 0으로 대체하지 않는다.

| 대상 | 최소 근거 |
| --- | --- |
| BE·FE source/artifact | 후보·최신 develop/FE SHA와 source 포함 근거, 동일 head 필수 CI, target image source tag/RepoDigest와 실제 readback 시각, Compose/Nginx hash, FE source/build 입력·dist/served hash·TEAM 계약/동작 |
| DB | Flyway version/script/정수 checksum/success, 실패 개수와 검토 파일 대조. V60–V63 테이블 존재와 기존 cohort/이메일/DOB 증거 보존은 승인된 합성 사례 범위에서 확인 |
| 검토 계정·정책 | 위 단일 계정의 private id/role/status/건수/확인 시각, 검토된 ID 목록, enabled/policyConfirmed/기간/범위/안내 버전·digest/전체 configurationDigest의 모든 인스턴스 일치. 회신 주소는 private binding 일치 여부만 공개 |
| 메일 | 현재 provider/enabled/From/region/base URL allowlist와 기존 secret 관리 버전 연속성 확인, outbox status/attempts/expired/due/inflight와 quota 집계, 기존 작업 처리·다른 producer 격리. 기존 SDK 시험 claim은 유지하며 추가 identity/SEND를 조회 증거로 요구하지 않음 |
| 보호자 정리 | state별 수량, overdue_contacts/evidence/events/operations와 연결된 notice 사본 수, worker 관찰 시각·실패 category·기한 모니터링, 외부 회신함/첨부·제공자·백업 삭제 계획. 실제 보호자 이름·연락처·신청/사용자 ID는 집계에서 제외 |
| 삭제·복구 | deletion intent kind/status/due 집계, private fresh backup 시각/hash/무결성·최신 trusted deletion journal 참조·복원 재적용 계획. 실제 백업/복구 또는 정리를 실행했다고 표시하지 않음 |

## V63 이후 전환·복구 경계

BE source CI는 target Docker image를 만들지 않는다. develop push의 기존 workflow는 image build/push→컨테이너/Nginx 재생성→Main startup Flyway를 수행하므로 target image 준비 경로·같은 FE 창·fresh backup·취소 담당과 범위가 별도 승인돼야 한다. 기존 짧은 signup/Google pause 승인은 임의의 장기 차단이나 DB/메일 동결 승인이 아니다. 아직 준비되지 않은 창을 먼저 닫지 않는다.

V60 DOB withdrawal cleanup, V61–V63 guardian 신청/증거/메일 정리 hook, TEAM_APPROVED/GUARDIAN_TEAM_NOTICE reader, 현재 동의 epoch/digest gate와 TURN usage 기록을 보존한 roll-forward를 준비한다. TEAM 데이터가 있는 DB를 옛 enum reader로 직접 downgrade하거나 schema DROP/Flyway repair로 우회하지 않는다. 복원 뒤 현재 삭제 원장과 현 withdrawal hooks의 재적용·정리 집계를 확인해야 한다. 실제 외부 메일함·제공자 파기와 운영 복원 성공은 코드 시험으로 대체할 수 없다.

## 남은 결정과 필요한 결과의 구분

사용자/정책 담당자의 추가 검토는 [관계·최종 고지/선택 AI·승인 및 증거 기간·외부 사본 파기 검토안](guardian-team-policy-review.md)의 채택/조정이다. 회신/검토 계정과 발신자, 업체 없는 경로, KST 규칙, 미확인 연락처 5일을 다시 묻지 않는다. 실행 단계에서는 서비스 메일 범위/예산, 기존 outbox 처리, FE/BE 전환 창·대상 image/복구 담당을 별도로 정해야 한다.

실제 ACTIVE ADMIN 존재 여부, 서버 image/Flyway/config/집계·backup metadata, FE artifact/TEAM 지원은 **운영자/FE가 제출할 증거**다. 값이 없다고 사용자에게 정책을 다시 선택하게 하거나 코드가 성공으로 채우지 않는다. 현재 source·문서 준비 완료와 운영 활성화/출시 완료를 구분해 보고한다.
