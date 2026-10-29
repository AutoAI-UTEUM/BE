# 실제 BE schema 기반 로컬 MySQL adapter

NOTE-04 / #479 / draft PR503의 로컬 구현이다. **DEV 실행·접속 승인이 아니며 기존 DB로 연결할 기능이 없다.**
2026-10-04의 develop `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`에 있는 V1–V53 migration SQL을
변경 없이 새 MySQL schema에 적용한다. 재개 시 읽은 PR495 후보
`e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`의 V1–V59도 별도로 지원/검증한다.
둘 모두 mutable checkout 대신 정확한 Git commit의 SQL을 읽는다. Spring/Flyway 앱, Docker, Gradle, 실제 인증/AI/메일/SMS를 실행하지 않는다.
SQLite 모델과 달리 실제 `users`, `learning_materials`, `material_pages`, `learning_sessions`, `quizzes`,
`quiz_submissions` 및 session FK graph의 JSON·DATETIME(6)·InnoDB 잠금·transaction을 실행한다.

## 구성과 실행 환경

| 파일 | 책임 |
| --- | --- |
| [local-mysql.mjs](../../../scripts/qa/note04-fixture/local-mysql.mjs) | 고정 schema 설치와 소유한 MySQL process 시작/검증/종료. 기존 target 수락 금지 |
| [local-assets.mjs](../../../scripts/qa/note04-fixture/local-assets.mjs) | 빈 로컬 schema에 non-loginable 합성 자산만 준비하고 generated IDs로 입력 계약 작성 |
| [mysql-adapter.mjs](../../../scripts/qa/note04-fixture/mysql-adapter.mjs) | supplied IDs 사전조건, prepared INSERT, 원장, 멱등 회수, 기본 rollback, 정확한 quiz 정리 |
| [local-ledger.sql](../../../scripts/qa/note04-fixture/local-ledger.sql) | 로컬 QA 원장 DDL. runtime Flyway migration/승인된 DEV DDL이 아님 |
| [mysql-rehearsal.mjs](../../../scripts/qa/note04-fixture/mysql-rehearsal.mjs) | 단독 실행 CLI. 기본 dry-run, 외부 target/credentials/apply 옵션 거절 |
| [mysql-adapter.test.mjs](../../../scripts/qa/note04-fixture/mysql-adapter.test.mjs) | 실제 MySQL의 경합·연결 실패·원자성·grant 거절·정리·범위 보존 검증 |

검증 환경은 Windows, Node 24.12.0, 설치된 MySQL Community Server/client 8.0.43이다.
Git은 독립 clone의 immutable SQL source 읽기에만 사용하며 global 설정·작업 branch를 바꾸지 않는다.
adapter의 shared-memory/최소권한 잠금 조건은 Windows MySQL 8.0 계열(8.0.22 이상)이다.
기본 binary 경로는 `C:\Program Files\MySQL\MySQL Server 8.0\bin`; 다른 설치 경로는
`--mysql-bin-dir=C:\path\to\bin`으로 지정할 수 있다. 설치/download나 PATH/global 설정 수정은 하지 않는다.

실행기는 `--no-defaults`, `--skip-networking`, `--mysqlx=OFF`, named-pipe OFF, 새 datadir,
고유 `NOTE04_LOCAL_*` shared-memory 이름으로 시작한다. client도 `--no-defaults`, `--protocol=MEMORY`,
고유 채널, local-infile OFF, reconnect OFF를 사용한다. 별도 빈 login-file 경로와 제한된 child env로
기존 `.mylogin.cnf`, `.env`, MYSQL/JDBC/DB credentials 환경값의 사용을 방지한다.
TCP/서비스명/기존 datadir/DB 이름/URL/port/credential/DEV/apply 입력은 받지 않는다.
factory의 WeakMap capability, 등록된 `note04_local_*` schema, 실제 datadir·채널·버전·TCP 비활성화를
매 connection에서 확인한다. 이름 prefix만 맞춘 임의 connection도 수락하지 않는다.

InnoDB buffer pool 32MiB, log buffer 8MiB, redo capacity 32MiB, max connections 8,
performance_schema OFF로 실행한다. 하나의 private process만 사용하고 tests는 serial로 실행한다.
테스트 suite는 migration을 한 번 적용하고 local test-owned rows를 각 scenario 앞에서 초기화한다.
종료 시 소유한 process만 SHUTDOWN하고, 생성한 Temp 하위 `note04-mysql-*` 단일 경로를 확인 후 제거한다.
기존 MySQL 서비스/계정/datadir·Docker·TCP 포트는 조회하거나 조작하지 않는다.
CLI의 local commit도 이 종료 후에는 fixture가 남지 않는다.

## 재사용 가능한 실행 명령

저장소 루트에서 다음을 실행한다. 두 번째 명령의 확인 문자열은 **로컬 합성 DB만**의 commit/정리 확인이다.

```powershell
node scripts/qa/note04-fixture/mysql-rehearsal.mjs
node scripts/qa/note04-fixture/mysql-rehearsal.mjs --mode=local-rehearsal --local-approval=LOCAL_SYNTHETIC_COMMIT --cleanup-local-approval=LOCAL_SYNTHETIC_DELETE
node scripts/qa/note04-fixture/mysql-rehearsal.mjs --schema=pr495-v59
node --test scripts/qa/note04-fixture/mysql-adapter.test.mjs
```

두 schema는 `--schema=develop-v53`(기본) / `--schema=pr495-v59`로 선택한다. arbitrary ref/schema/target은 거절한다.
독립 clone에 후보 object가 없으면 read-only `git fetch --no-tags origin e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`로
먼저 확보한다. 이 fetch는 GitHub source 읽기이며 DEV 접속이 아니다. 실행기 자체는 fetch/download하지 않고
lazy fetch와 terminal prompt를 비활성화한다. 정확한 SHA의 V1..53 또는 V1..59만 허용한다.

기본 실행은 합성 자산 8개와 별도 CONTROL 자산을 local 준비한 뒤, quiz 103개+원장을 하나의 transaction에서
생성·검증하고 rollback한다. 자산 준비는 test harness의 단계이며 quiz adapter의 INSERT 범위에 포함하지 않는다.
CONTROL 1 user/1 material/1 session/1 quiz는 대상 외 보존 검사용으로 fixture 2/3/3/103 집계에서 제외한다.
실제 계정의 signup/동의/verified/READY 절차를 SQL로 우회하는 운영 절차가 아니다.

## 런타임 입력 계약과 manifest

[입력 예시](input-contract.example.json)의 null은 미확정값이다. 그대로 실행할 수 없으며 validator가 거절한다.
`validateInput`은 exact keys만 허용하며 user/material/session IDs는 canonical 양의 signed BIGINT **문자열**이다.
null/숫자/leading zero/중복/범위 초과·A/B 소유관계 오류·잘못된 UTC DATETIME(6)을 거절한다.
`schemaSourceCommit`도 두 pinned SHA 중 하나여야 하며 실제 local schema의 factory 기록과 일치해야 한다.
이 값이 canonical approved scope hash에 포함되어 같은 숫자 IDs를 V53/V59 간 재사용할 수 없다.
`expectedVersion`과 각 자료의 승인된 `expectedMaterialUpdatedAtUtc`도 반드시 공급한다.
조회 결과로 ID를 추측/보충하거나 email/title/최근 생성 목록으로 자산을 찾지 않는다.

로컬 모듈 사용 순서는 다음과 같다. `owner`와 `database`는 factory가 새로 만든 것만 가능하다.

```javascript
const owner = await startLocalMysql();
try {
  const database = await createLocalSchema(owner);
  const input = await prepareLocalAssets(owner, database); // only synthetic local assets
  const result = await seedMysql(owner, database, input);  // default rollback
} finally {
  await stopLocalMysql(owner);
}
```

manifest에는 `environment=LOCAL_OWNED_MYSQL_SCHEMA`, run/manifest ID, canonical approved input hash,
state, schema source SHA/count, 2/3/3/103·101/1/1 counts, UTC time, REUSED_PROTECTED 8개와 CREATED quiz103개의 실제 로컬 generated ID,
owner/session 링크 및 row hash를 담는다. JSON 답안 원문·이메일·password·token·PDF·동의는 export하지 않는다.
local ID/hash를 DEV 승인 manifest로 복사하면 안 된다. dry-run도 auto-increment ID를 소비할 수 있으므로
rollback은 연속 ID 보장이 아니다. dry-run의 가상 ID는 commit된 정리 대상이 될 수 없다.

## 사전조건·원장·정리

사전조건은 승인 input의 A/B LEARNER/ACTIVE/미정지, A/A/B 소유 READY 자료와 page7 존재,
정확한 3개 ACTIVE/NOT_EXPLAINED/page7/version 세션, active turn/quiz/diagnosis 없음,
미삭제 own-session inventory A={A1,A2}, B={B1}, quiz와 지정 session descendants가 비어 있음이다.
불일치는 INSERT/자동 수선으로 해결하지 않는다. 실제 준비 자산이 이 조건을 충족하는지는 UNKNOWN이다.

실제 OX INSERT는 V5 열에 대해 `PREPARE/EXECUTE USING`으로 바인딩한다. public/private 구조는
BE `QuizPublicData`, `PublicQuizQuestion`, `QuizPrivateData`, `PrivateQuizQuestion`의 이름을 사용한다.
OX choices는 생략하고 문항1/10점, page7/coverage1..7/schema1.0을 생성한다. A1의 101개에 같은
UTC DATETIME(6)을 INSERT 시 공급한다. UPDATE로 생성시각을 맞추지 않는다.

원장은 같은 DB의 `qa_fixture_runs`(run PK/manifest UNIQUE)와 `qa_fixture_run_resources`(정확한111 ID 관계)다.
BUILDING INSERT→잠금/사전조건→quiz103 INSERT/generated IDs→invariants→resource111 INSERT→
COMMITTED+DB manifest/hash UPDATE→**단일 COMMIT**이다. DDL은 준비 단계에만 있으며 seed transaction 안에 없다.
같은 run의 두 connection은 unique INSERT로 직렬화된다. 중복은 transaction 종료 후 기존 DB manifest를
회수하고 scope/hash/resource membership/행수/미제출을 검증한다. COMMITTED 또는 CLEANED면 재생성하지 않는다.
commit 응답이나 파일 export를 잃어도 원장 확인 전에 다시 INSERT하지 않는다.

보호 자산과 관련 metadata에는 `FOR SHARE`, 변경할 run에는 `FOR UPDATE`를 쓴다.
이는 보호 자산의 DML 권한을 부여하지 않고 변경을 막기 위한 선택이다.
[MySQL 잠금 권한 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)에 따라
8.0.22 이상 FOR SHARE는 SELECT만 필요하지만 FOR UPDATE는 추가 권한이 필요하다.
[MySQL implicit commit 문서](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)의 DDL 특성 때문에
원장 설치를 생성 transaction과 분리한다.

정리는 approved manifest hash와 DB 원장을 비교하고 정확한 CREATED quiz103 ID만 DELETE한다.
ROW_COUNT=103, 상태/소유/row hash/제출 없음/descendants 없음, 보호 자산 hash를 재검사한다.
기본은 rollback이고 중간 DELETE 실패도 모두 rollback한다. 로컬 승인 성공 때는 CLEANED로 바꾸며
run/resource111 ID 원장을 보존한다. 재사용 자산8은 DELETE하지 않는다. 실제 영구삭제 권한은 별도다.

## 로컬에서 검증한 권한과 실환경에 남은 최소 승인

로컬 installer만 schema/합성 자산/역할을 준비한다. seed/cleanup connection은 root를 사용하지 않는다.
read columns의 exact 목록과 GRANT는 `createLocalSchema`에 있으며, 사용자의 email/password/token/consent에
SELECT 권한이 없다. metadata COUNT/HASH에 필요한 열만 허용한다.
seed는 quiz/run/resources INSERT와 run state/manifest UPDATE, cleanup은 quiz DELETE와 run state/manifest UPDATE만 가진다.
둘 다 자산 DML/DDL 권한이 없고, seed의 DELETE와 cleanup의 quiz INSERT도 실제 grant가 거절한다.

제한된 역할의 information_schema는 권한 없는 추가 child table을 숨긴다. 따라서 **완전한 FK graph/engine 검사는
로컬 installer의 별도 metadata connection**에서 수행한다. 이는 데이터 조회/쓰기 우회가 아니다.
운영에서는 별도 승인된 schema 검사/attestation 경로가 필요하며 제한 역할의 metadata 결과만으로
FK graph가 완전하다고 주장하면 안 된다. 현재 로컬 역할/DDL을 실제 서버에 설치하지 않았다.
column GRANT도 row allowlist를 강제하지 않는다. 실환경 job의 고정 ID 제한/audit 경로는 추가 검토 대상이다.
quiz JSON의 hash 계산에 필요한 SELECT 권한은 DB 수준에서 JSON 원문 읽기도 허용한다.
현재 코드의 hash-only 결과 제한과 SQL grant를 구분하며, 실환경에 더 좁은 hash view/승인 job이 필요한지도 검토해야 한다.

실환경으로 넘어가기 전에 필요한 입력/승인은 별개다:

1. 실제 배포/schema/FK/audit·원장 설치와 운영 adapter 구현/검토 승인. 이 실행기의 기존 target 차단을 해제해서 사용하지 않는다.
2. A/B user2·material3·session3의 전용 합성 증명/정확한 IDs·version/자료 updated_at·UTC 생성시각·run/manifest·사용/동결 기간.
3. 위 최소 metadata/own-session inventory 조회, quiz103 INSERT와 원장 쓰기 승인 및 제한 job/권한 경로. 광범위 credential 공유 없음.
4. 기존 auth 계약에 따른 본인 인증·FE GET 인수 승인/담당. 실제 신규 계정·동의·메일/credentials 전달은 각각 별도.
5. CREATED103만의 정리 시점/담당·행 상태 재검사와 별도 영구삭제 승인. BE 한승준/FE 이감재는 여전히 제안/미확정.

후보 V59에는 V54–V59의 mail outbox/email verification/deletion journal/age/cohort/guardian intake가 추가돼 있다.
로컬 새 합성 users도 migration 기본값 `NEW_SIGNUP`, email/age `UNKNOWN`, DOB/verified_at null을 그대로 둔다.
VERIFIED/LEGACY_EXEMPT/DOB/동의/guardian 승인을 만들어 넣지 않는다. 원장과 quiz 검증이 통과해도 이 users의
실제 로그인·FE 인수가 준비됐다는 뜻이 아니다. 메일/guardian/동의 테이블에 새 행이 생기지 않고 기존 row hash가 유지됨을 검증한다.

MySQL 로컬 검증은 [검증 기록](local-validation.md)에 남긴다. live DEV/MySQL schema 확인, Spring HTTP/auth,
브라우저 UI, mysqld/OS crash recovery, 실환경 제한 view/audit는 NOT_RUN이다.
