# 승인, 재사용, 최소권한과 manifest

## 현재 허용 범위와 미승인 항목

현재 허용된 작업은 BE의 격리된 문서/스크립트, 로컬 합성 DB, 독립 feature 브랜치 push/draft PR이다.
이감재의 준비 요청이나 과거 PR486 배포 승인을 이번 fixture의 DEV 조회/쓰기 승인으로 사용하지 않는다.
실환경 버전/설정·배포/복구 조건은 [PR501 문서](https://github.com/AutoAI-UTEUM/BE/blob/fd12b01ecfef34b45629e2a8efe4068a4b5861c1/docs/launch-deployment-preflight.md)를 기준으로 별도 담당이 확인한다.
PR495의 미배포 후보와 현재 DEV schema/auth를 같은 환경으로 간주하지 않는다.
전용 ID/허용 조회 열/권한과 실제 quiz INSERT·원자적 공유 원장의 선택안은 [실행계획](dev-execution-plan.md)에 있다.
현재 숫자 IDs·권한·원장 존재는 미확인이고 운영 adapter/DDL은 제공하지 않는다.
실제 schema용 [로컬 MySQL adapter/원장/grant](mysql-local-adapter.md)는 별도 제공했다. 아래 승인 표의 0회는
실환경 실행을 뜻하며 로컬 합성 DB의 CREATE/INSERT/DELETE와 분리한다.

| 단계 | 필요한 명시적 범위 | 현재 상태 |
| --- | --- | --- |
| 전용 자료 재사용 확인 | 승인된 A/B user ID와 A1/A2/B1 material/session ID의 최소 메타 조회, DEV 환경/담당/기간 | 대기; 실제 조회 0회 |
| fixture 생성 | run/manifest, 실제 ID 또는 신규 생성 목록, 2/3/3/103, 단일 transaction, 외부 호출 0회 | 대기; DEV 쓰기 0회 |
| 계정 생성 | 신규 LEARNER 2명의 생성 절차와 인증 경로; 기존 승인 계정 재사용 여부 | 별도 대기; 계정 생성 0회 |
| 동의/인증 전달 | 필요한 인증·동의 흐름, 본인이 처리할 주체, 비밀 전달 여부/대상/수단/기간 | 별도 대기; 자동 동의·credentials 공유 0회 |
| FE 실응답 조회 | 승인 계정으로 한정된 GET, 담당 FE, 관찰 기간, 증거 redaction | 대기; 실응답 인수 0회 |
| 정리 | 정확한 manifest IDs, CREATED/REUSED 구분, 상태 재검사, 정리 방법/담당/기간 | 대기; 정리 실행 0회 |
| 영구삭제 | 승인된 CREATED IDs의 삭제 graph와 물리 저장소 대상, 실행 시점 | **별도 승인 필요**; 생성 승인이 포함하지 않음 |

기존 본서비스 계정, 학생 PDF, 답안, 동의 레코드, SSH 키, DB credential은 준비 자료로 재사용하지 않는다.
승인이 없는 단계는 `PENDING`으로 남긴다. 생성 승인 대기 중 서버/DB 접속을 시도하지 않는다.

## 재사용 가능한 자료 판단

부모의 기존 `note04-dev-fixture-plan.json`과 #479 인계 내용을 계획의 출처로 재사용한다.
실제 자산의 재사용 가능 여부는 현재 **UNKNOWN**이다. 코드나 이 문서가 이를 확인했다고 주장하지 않는다.
조회 승인이 오면 다음 전용 IDs의 metadata만 읽어 아래 조건을 판정한다.

| 자산 | 승인 후 사전조건 | 재사용 시 처리 |
| --- | --- | --- |
| 학습자 A/B | 서로 다른 전용 합성 LEARNER, ACTIVE, 담당/사용 목적 확인; 현재 auth 정책을 충족하는 로그인 경로 | REUSED_PROTECTED, 신규 계정 생성 금지 |
| 자료 A1/A2/B1 | A/A/B 소유관계, ACTIVE/READY, 필요한 PDF 페이지 7 존재, 개인정보 없는 합성 자료, 외부AI 처리 필요 없음 | 변경 없이 REUSED_PROTECTED |
| 세션 A1/A2/B1 | A/A/B 소유관계, 해당 자료 연결, ACTIVE, 실행 중 턴/활성퀴즈 없음, 빈 quiz/dependent 집합 | 변경 없이 REUSED_PROTECTED |

필수값이나 관계가 다르면 자동 수선/추가 생성하지 않고 중단한다. 재사용 가능한 계정·자료가 없으면
필요한 신규 생성 범위를 승인 요청 목록에 추가한다. 기존 계정에 역할·DOB·메일인증·동의 값을 임의 설정하지 않는다.
가입/메일확인/동의가 필요한 경우 기존 인증 기능 담당과 안전한 합성 절차를 먼저 결정한다.
PDF upload나 학습 턴으로 AI를 호출하여 101개를 만들지 않는다. 미승인 외부 저장소 파일도 만들지 않는다.

## 최소권한

- metadata 확인자는 승인된 전용 ID의 소유/상태/행수/연관성만 읽는다. credential/PDF/답안 원문/동의 원문을 export하지 않는다.
- 실행자는 별도 검토된 일회성 작업에서 명시된 신규 synthetic ID만 생성한다. 글로벌 ADMIN,
  schema 변경, 광범위 UPDATE/DELETE, 기존 자료 수정 권한을 요구하지 않는다.
- 신규 quiz 생성은 승인된 빈 세션에 INSERT만 허용한다. 기존 자산에는 SELECT/잠금만 사용한다.
  실제 schema의 필수 JSON/제약은 승인된 BE adapter에서 확인한다. 이 mock의 `fixture_*` DDL을 DEV에 실행하지 않는다.
- 두 FE 주체는 자신의 합성 LEARNER 인증만 사용한다. 검증 대상 목록 GET을 사용하며
  퀴즈 생성/제출/학습 턴/메일/SMS 호출 권한을 추가하지 않는다.
- 정리 담당 권한은 생성 권한과 분리한다. 승인된 CREATED IDs와 필요 FK 조회에만 한정한다.
  재사용 자산과 현재 fixture 요구를 벗어난 descendants는 자동 정리하지 않는다.
- 소유권·범위 증거는 hash와 건수로 남긴다. 대상 외 전체 DB를 읽을 권한을 새로 넓히지 않는다.
  DEV에서 대상 외 변경을 검증할 audit/변경 기록 수단이 없으면 그 보장을 미검증으로 표기하고 실행 판단을 부모에 반환한다.

## 실행 manifest 구조

SQLite 실행기가 출력하는 manifest는 `environment=local-synthetic-memory`, `idsAreDevIds=false`,
`devExecuted=false`, 모든 approval=false다. 로컬 정리만을 위한 connection 원장/행 hash에 묶인다.
이 값을 편집하거나 숫자 IDs를 복사해 실제 환경용 승인 manifest로 사용하지 않는다.
MySQL 로컬 manifest도 `environment=LOCAL_OWNED_MYSQL_SCHEMA`로 표시하며 real DEV ID/승인이 아니다.
그 원장은 재사용8+생성103의 관계111개를 보존하고 cleanup은 quiz103만 대상으로 한다.

실제 승인 후에는 다음 **별도 실행 manifest**를 채워 검토한다. 지금은 모든 실환경 ID/승인이 미정이다.

```json
{
  "schemaVersion": 1,
  "manifestId": "note04-dev-20261004-r1-plan",
  "runLabel": "NOTE04-DEV-20261004-R1",
  "phase": "PLANNED_NOT_EXECUTED",
  "environment": "DEV_PENDING_APPROVAL",
  "beDeployedSha": null,
  "feAcceptanceSha": null,
  "approvals": {
    "metadataReadRef": null,
    "fixtureWriteRef": null,
    "accountCreationRef": null,
    "consentAndAuthenticationRef": null,
    "credentialHandoffRef": null,
    "cleanupRef": null,
    "permanentDeletionRef": null
  },
  "actors": [
    { "alias": "A", "userId": null, "disposition": "UNDECIDED" },
    { "alias": "B", "userId": null, "disposition": "UNDECIDED" }
  ],
  "resources": [
    { "alias": "A1", "ownerAlias": "A", "materialId": null, "sessionId": null, "expectedQuizCount": 101, "quizIds": [], "disposition": "UNDECIDED" },
    { "alias": "A2", "ownerAlias": "A", "materialId": null, "sessionId": null, "expectedQuizCount": 1, "quizIds": [], "disposition": "UNDECIDED" },
    { "alias": "B1", "ownerAlias": "B", "materialId": null, "sessionId": null, "expectedQuizCount": 1, "quizIds": [], "disposition": "UNDECIDED" }
  ],
  "snapshots": { "beforeScopedHash": null, "afterScopedHash": null, "outsideChangeAuditRef": null },
  "expected": { "learners": 2, "materials": 3, "sessions": 3, "quizzes": 103, "submissions": 0 },
  "a1CreatedAt": null,
  "generationPath": "REUSE_APPROVED_ASSETS_INSERT_103_QUIZZES",
  "readScope": { "userIds": [], "materialIds": [], "sessionIds": [], "approvedScopeSha256": null },
  "runLedger": { "location": null, "schemaApprovalRef": null, "adapterApprovalRef": null, "ready": false },
  "rowImagesByExactId": {},
  "createdIdsByTable": {},
  "reusedProtectedIdsByTable": {},
  "dependentCountsByTable": {},
  "executionOwner": "BE 한승준 (제안/미확정)",
  "cleanupOwner": "BE 한승준 (제안/미확정)",
  "feAcceptanceOwner": "FE 이감재 (제안/미확정)",
  "expiresAt": null,
  "cleanupState": "NOT_APPROVED",
  "devExecuted": false
}
```

`rowImagesByExactId`는 소유관계·상태·연결·생성시각·공개/비공개 JSON hash의 기준값과 변경판정 범위를 담는다.
정답/비밀번호/token을 넣지 않는다. 생성된 실제 quiz ID는 transaction 안에서 얻은 103개 정확한 목록을 기록한다.
`createdIdsByTable`와 `reusedProtectedIdsByTable`를 분리하고 table별 expected/actual 행수,
보존해야 할 IDs, 생성 시각, seed hash, approval reference, 담당, 사용/동결 기간을 함께 확정한다.
권한을 담는 인증 비밀은 manifest로 전달하지 않는다.

## 생성 전/후 invariants와 중복 방지

1. 승인된 환경/배포 SHA/schema/대상 IDs/담당이 일치하는지 확인한다. 같은 run label 또는 manifest ID가
   이미 실행/진행/정리 기록에 있으면 재실행하지 않고 기존 manifest를 인계한다.
   로컬 중복 검사는 동일 메모리 DB 안에서만 작동한다. 프로세스 간 DEV 중복 방지를 입증한 것이 아니다.
2. 실제 실행에는 공유된 승인 작업 원장과 run 단위의 단일 실행권/잠금을 먼저 확보해야 한다.
   선택안은 같은 MySQL/InnoDB connection의 unique run 원장 + 103 quiz INSERT + manifest 관계 기록을 한 transaction에 commit하는 것이다.
   중복/commit 응답 유실은 DB 원장의 기존 IDs를 읽어 처리하며 재생성하지 않는다.
   원장 설치/adapter 승인·구현이 없으면 DEV seed하지 않는다. 이 작업은 원장 migration을 추가하지 않는다.
3. transaction에서 대상 행 잠금과 소유/빈 세션/필수 상태를 다시 검사한다. 기존 A1/A2/B1 quiz 수는 0,
   submission/assessment/diagnosis/chat/note 및 그 밖의 seed/cleanup 관련 descendants는 없어야 한다.
   재사용 자산의 row image를 보존하고 외부 처리 작업이 시작되지 않아야 한다.
4. 신규 생성 ID만 manifest에 append한다. 2/3/3 자산은 승인된 CREATED+REUSED 총계이고
   quiz는 103개의 새 합성 행이다. A1=101, A2/B1=각1, 전부 OX/미제출, A1 동일 `created_at`, 관계 A/A/B,
   시작/종료 coverage가 실제 페이지 범위 안에 있어야 한다. 광범위 timestamp UPDATE는 금지한다.
5. 신규 IDs·각 세션 전체 quiz count·상태·생성시각·반환 DTO를 commit 직전에 확인한다.
   재사용 행 hash와 audit에서 대상 외 변경 0을 확인한다. 검사가 실패하거나 예상 밖 행/외부 작업이 있으면
   **전체 ROLLBACK**한다. 사전부터 존재하던 불일치 데이터는 수선하지 않는다.
6. commit 후 같은 승인 주체로 읽기 검사를 수행하고 확정 ID/행수/상태를 manifest에 기록한다.
   여러 GET은 offset 방식이므로 fixture를 동결하고 조회 중 새 quiz 생성/제출/정리를 막는다.
   동결 없이 snapshot 연속성이나 중복 없음이 보장된다고 보고하지 않는다.

## 정리 계획

제안 정리 담당은 **BE 한승준**, 인수 확인 담당은 **FE 이감재**다. 아직 담당 확정/정리 승인이 없다.
생성 승인이 나도 인수 완료 후 자동 삭제하지 않는다. 실제 파일·계정 삭제를 fixture 정리와 묶지 않는다.

정리 전 정확한 실행 manifest, 별도 승인, 보존 기한, 예상 삭제 graph를 검토한다.
실행 시 당시 실제 FK/서비스 cascade 및 quiz submission/assessment/diagnosis/note/chat 등 모든 연관 테이블을 확인한다.
추가 데이터, 상태/소유권/행수/hash 불일치 또는 REUSED 자산 삭제가 필요하면 rollback하고 범위를 재검토한다.
승인된 CREATED ID마다 `id + owner/session + expected state` 조건과 예상 affected row count를 검사한다.
run label/title/created_at 범위만으로 삭제하지 않는다. 알 수 없는 FK에 맞추어 cascade 권한을 넓히지 않는다.
대상 외 변경 0을 감사 증거로 확인하고, 정리 후 run 기록은 남겨 같은 label로 신규 batch를 만들지 않는다.

로컬 `cleanupFixture`의 111행 삭제/rollback 검증은 메모리 DB의 103+3+3+2 CREATED 모델에만 해당한다.
DEV에서는 REUSED 자산 수, auth/storage/dependent graph가 다를 수 있어 111행을 삭제 예상값으로 복사하지 않는다.
실제 영구삭제의 승인/실행 adapter는 제공하지 않는다.
