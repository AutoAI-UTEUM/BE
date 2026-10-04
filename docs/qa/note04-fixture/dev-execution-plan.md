# NOTE04의 전용 ID·메타 조회·생성·원자적 run 원장 계획

**계약/실행계획 검토용이며 DEV adapter가 아니다.** 실제 ID, DB 유효 schema, 권한, 공유 원장의 존재를 조회하지 않았다.
서버/DB 접속·seed·계정 생성·원장 DDL·정리·영구삭제는 실행하지 않았다. 아래 설계도 승인 대기다.
현재 `fixture.mjs`는 SQLite `:memory:`만 사용한다. 작업 재개 후 [별도 로컬 MySQL adapter](mysql-local-adapter.md)를
실제 develop V1–V53 및 pinned PR495 후보 V1–V59 schema 대상으로 구현·검증했다. 이 adapter도 본인이 만든 임시 process만 사용하며
기존 DEV DB로 연결할 기능이 없다. 운영 adapter·원장 설치는 여전히 별도 승인/통합 대상이다.
배포/auth/schema 확인 순서는 [PR501 고정 런북](https://github.com/AutoAI-UTEUM/BE/blob/fd12b01ecfef34b45629e2a8efe4068a4b5861c1/docs/launch-deployment-preflight.md),
목록의 101개/정렬/404/400 계약은 [PR481 절차](https://github.com/AutoAI-UTEUM/BE/pull/481#issuecomment-5969927454)를 재사용한다.

## 선택할 실제 생성 경로

우선 경로는 **승인된 전용 합성 학습자 2명·READY 자료 3개·빈 세션 3개를 재사용하고 OX quiz 103개만 새로 INSERT**하는 것이다.
fixture 총계 2/3/3/103과 실제 신규 INSERT 수를 구분한다. 재사용한 8개 자산은 변경/삭제에서 보호한다.
현재 재사용 가능 여부는 UNKNOWN이며 가능한 자산이 있다고 주장하지 않는다.

승인된 IDs/빈 세션/합성 PDF 페이지가 없으면 `ASSET_PREPARATION_BLOCKED`로 중단한다.
신규 계정·자료·세션의 준비는 각각 별도 승인을 받은 절차로 먼저 완료하고 해당 IDs를 이번 run의 REUSED_PROTECTED로 편입한다.
계정은 auth/동의/이메일 확인을 우회하는 SQL INSERT로 만들지 않는다. 자료를 임의 READY로 표시하거나
실 upload/AI extraction/메일/SMS를 실행하지 않는다. 새 자산까지 같은 transaction에서 만들겠다고 약속하지 않는다.
그 범위가 요구되면 승인 대상 생성 graph와 별도 adapter 설계를 다시 검토해야 한다.

quiz 생성은 승인 후 별도로 검토할 일회성 BE 작업에서 **같은 MySQL connection의 parameterized INSERT**로 한다.
공개 API의 학습 턴/퀴즈 생성 기능을 호출하지 않는다. 공개 seed API도 추가하지 않는다.
현재 실제 근거는 [V5 quizzes schema](../../../main-service/src/main/resources/db/migration/V5__quizzes.sql),
[QuizPublicData](../../../main-service/src/main/java/io/edupilot/quiz/QuizPublicData.java),
[PublicQuizQuestion](../../../main-service/src/main/java/io/edupilot/quiz/PublicQuizQuestion.java),
[QuizPrivateData](../../../main-service/src/main/java/io/edupilot/quiz/QuizPrivateData.java)다.

검토할 INSERT 열은 `session_id`, `page_number`, `title`, `coverage_start_page`, `coverage_end_page`,
`quiz_type`, `public_question_json`, `private_answer_json`, `schema_version`, `created_at`이다.
세션은 승인된 A1/A2/B1 ID만 사용하고 각각 101/1/1회를 삽입한다. 생성 ID는 DB generated key로 회수한다.
`quiz_type=OX`, page/coverage=7/1..7, `schema_version=1.0`, 결정적인 합성 문항 1개/10점으로 한다.
공개/비공개 JSON은 당시 BE record 이름으로 직렬화하고 문항 ID·문항 수·점수·합성 답안을 로컬 검증한다.
OX는 MCQ choices가 없다. 답안 JSON은 DB 내부에만 저장하고 공개 목록/manifest/로그에 출력하지 않는다.
합성 전용 제목은 run+alias+ordinal로 식별하지만 ID 제한을 제목 검색으로 대신하지 않는다.
같은 승인된 UTC `DATETIME(6)` 값을 A1 101개의 INSERT에 직접 바인딩한다. 기존 행/세션 전체 timestamp UPDATE는 하지 않는다.
현재 SQLite mock DDL/숫자 IDs를 MySQL에 복사하지 않는다. 실제 V5 INSERT와 generated IDs를 검증한
로컬 adapter는 구현됐으나, **운영용 일회성 adapter는 미구현·미승인**이다.

## 승인할 정확한 ID 목록

자산 담당이 전용 합성 용도/보관 기간을 확인한 실제 IDs를 먼저 제시해야 한다. 계정 이메일/비밀번호로 찾거나
전역 목록·최근 생성 시각·run 제목을 검색해 임의로 IDs를 선택하지 않는다. 아래 null은 미정값이고 실행 입력이 아니다.

| alias | user ID | material ID | session ID | 관계/예상 quiz 수 |
| --- | --- | --- | --- | --- |
| A1 | A.userId = null | M_A1 = null | S_A1 = null | A→M_A1→S_A1, 생성 전 0 / 후 101 |
| A2 | 동일 A.userId | M_A2 = null | S_A2 = null | A→M_A2→S_A2, 생성 전 0 / 후 1 |
| B1 | B.userId = null | M_B1 = null | S_B1 = null | B→M_B1→S_B1, 생성 전 0 / 후 1 |

A/B는 서로 다르고, material/session 3개도 각각 고유해야 한다.
FE 복습 화면은 본인의 모든 미삭제 세션을 자동 조회하므로 A의 미삭제 세션 집합이 S_A1/S_A2,
B의 집합이 S_B1과 정확히 일치해야 한다. 추가 미삭제 세션이 발견되면 그 quiz를 읽지 않고 인수를 중단한다.
이는 자료/세션 개별 ID 확인에 더해 승인할 **A/B 소유 세션 inventory의 id/status 최소 조회**다.
승인 manifest에는 이 2/3/3 정확한 IDs, 각 자산 REUSED_PROTECTED 표시, run/manifest ID,
UTC 생성시각, expected counts, 합성 목적 증명 reference, 환경/배포/schema, 승인/담당/동결 기간을 담는다.
이 값들을 canonical scope hash로 고정하고 실행 시 변경하지 않는다. actual quiz ID 103개는 transaction에서 회수한다.
승인되지 않은 row, 같은 ID를 다른 소유자로 재지정, 빈 값 자동 보충은 거절한다.

## 최소 metadata 조회 대상/권한

현재 이 조회는 모두 미승인·미실행이다. 자산 확인자는 별도 승인된 운영 경로에서 다음 bound ID 조회만 한다.
자료 본문·파일키·답안·인증 비밀·개인 이메일·동의 원문·token 테이블을 조회하지 않는다.

| 대상 | 허용할 열/집계 | 고정 row 범위/판정 |
| --- | --- | --- |
| `users` | id, role, status, 정지 여부 boolean | 승인 A/B 2개; 서로 다른 전용 합성 LEARNER/ACTIVE |
| `learning_materials` | id, owner_id, status, processing_status, page_count, version에 필요한 최소 updated_at | 승인 M_A1/M_A2/M_B1 3개; A/A/B 소유, ACTIVE/READY, page_count≥7 |
| `material_pages` | material_id/page_number별 존재/건수만 | 위 material 3개, page7 존재; text_content/파일 내용 미조회 |
| `learning_sessions` | id, user_id, material_id, status, current_page, version, active-turn/active-quiz/pending-diagnosis 부재 boolean | 승인 S_A1/S_A2/S_B1 3개; A/A/B·연결 일치, ACTIVE, page7, 작업 없음 |
| A/B 소유 session inventory | id/status와 미삭제 count만 | 승인 user A/B에 한정; 미삭제 집합 A={S_A1,S_A2}, B={S_B1}; 추가 세션 발견 시 중단, 해당 quiz/본문 조회 없음 |
| `quizzes` | 생성 전 session별 count; 생성 후 정확한 id/session/type/page/coverage/schema/created_at와 JSON hash | 위 session 3개만; 0→101/1/1, private/public JSON 원문 export 없음 |
| `quiz_submissions` | 대상 quiz의 count만 | 위 session의 quiz에 JOIN; 모든 주체의 제출 수 0 |
| session descendants | `chat_messages`, `qa_threads`, `session_page_records`, `notes`, `quiz_assessments`, `diagnoses`, `repair_results` 건수만 | 위 session 3개만; 기대 0, 내용 미조회. 실제 적용 schema의 FK graph에 추가 관련 테이블이 있으면 승인 범위를 먼저 갱신 |
| 공유 QA run 원장 | 정확한 run/manifest의 상태, 승인 scope hash, ID/행수/manifest hash | 이번 run만; 다른 실행 목록 미조회 |

신규 auth/cohort/email/guardian 열은 실제 배포 schema가 지원하고 승인 범위에 포함된 경우에만 상태 boolean/enum으로 추가한다.
PR495 후보의 열이 DEV에 있다고 가정해 조회하지 않는다. 본인 로그인 가능성은 승인된 담당자가 기존 인증 절차로 확인한다.
인증 readiness 확인 자체의 허용 범위는 [auth handoff](fe-handoff.md)와 PR501/502를 따른다.

READ 단계는 위 열/COUNT/HASH만 반환하는 검토된 bound queries가 필요하다. SQL SELECT grant는 MySQL에서
row allowlist를 자동 강제하지 않으므로 검토된 작업/승인된 제한 view 등으로 정확한 ID 범위도 제한해야 한다.
광범위 ADMIN/DB credential 공유를 요구하지 않는다. 실제 운영 경로/권한/제한 view 구성은 미확정이다.

쓰기 역할은 `quizzes INSERT`, QA 원장 INSERT/해당 run UPDATE, 원장 resource INSERT와 위의 SELECT/row lock만 필요하다.
계정/자료/세션 UPDATE·INSERT·DELETE, auth token/consent 변경, storage/메일/SMS/AI 권한을 이번 quiz 작업에 포함하지 않는다.
원장 schema 설치권한은 별도 단계이며 실행자에게 DDL 권한을 주지 않는다. 현재 권한을 만들거나 확인하지 않았다.

## 데이터와 원장의 원자성 — 로컬 구현 / 실환경 설치 미승인

공유된 파일 manifest나 GET_LOCK만으로는 commit 후 파일 export 전 실패를 식별할 수 없다.
선택안은 **quiz와 같은 MySQL/InnoDB DB·같은 JDBC connection·autocommit=false**의 공유 원장이다.
로컬 QA 원장 DDL과 adapter는 전용 scripts에 제공했다. DEV 원장의 존재 여부는 확인하지 않았다. 설치/DDL/migration 승인은 별도이며
최종 통합 담당이 schema/adapter를 검토해야 한다. 원장이 없으면 DEV seed는 차단한다.
MySQL DDL은 implicit commit될 수 있으므로 원장 CREATE/ALTER를 seed transaction 안에 넣지 않는다.

| 제안 원장 | 최소 구조/제약 | 의미 |
| --- | --- | --- |
| `qa_fixture_runs` (가칭) | run_label PK, manifest_id UNIQUE, approved_scope_sha256, environment/schema reference, state, manifest hash, actual counts, timestamps, 승인/담당 reference | run 하나의 단일 생성과 승인 범위 고정. state BUILDING→COMMITTED는 아래 하나의 transaction 안에서만 수행 |
| `qa_fixture_run_resources` (가칭) | run FK, kind/alias/resource_id, owner/session link, CREATED/REUSED_PROTECTED, row hash; UNIQUE(run,kind,resource_id) | 정확한 생성 quiz 103개와 보존할 자산 8개의 ID 관계를 DB에 기록. 인증/답안 원문 없음 |

운영 승인 후의 계획 순서이며 실행 가능한 DEV script가 아니다. 같은 원자적 순서를 로컬 MySQL에서 검증했다:

1. 환경/schema/승인 scope/전용 IDs/동결 기간/원장 schema readiness를 확인한다.
2. **BEGIN**하고 이번 run_label/manifest_id의 BUILDING 원장 행을 INSERT한다. unique key가 동일 run의
   다른 connection을 직렬화한다. 별도 transaction에서 BUILDING만 먼저 commit하지 않는다.
3. A/B→materials→sessions를 고정 ID 순서로 잠그고 최소 metadata와 빈 quiz/dependent 집합을 다시 검사한다.
   단일 실행권을 가졌다는 사실만으로 소유/상태 사전조건을 생략하지 않는다.
   승인된 A/B inventory와 FE GET 범위가 같아야 하며 검증 동안 추가 세션 생성도 동결한다.
4. 검토한 합성 JSON과 같은 UTC 생성시각을 바인딩하여 quiz 103개를 INSERT하고 generated IDs를 회수한다.
   각 ID의 owner/session 링크·row hash와 REUSED_PROTECTED 8개를 동일 transaction의 resource 원장에 기록한다.
5. 동일 transaction에서 101/1/1, 103 unique IDs, OX/미제출/동일 A1 시각, DTO 공개 계약, 보호 자산 hash,
승인된 관련 행만 변경됐다는 작업/audit 기록을 확인한다. 조건 불일치·timeout·deadlock·제약/serialization 오류는 전체 ROLLBACK이다.
6. DB의 실행 manifest/ID 관계/hash/count를 완성하고 원장 state를 COMMITTED로 바꾼 뒤 **한 번 COMMIT**한다.
   외부 API/메일/AI/SMS/파일쓰기와 이 transaction을 섞지 않는다.
7. commit 후 DB 원장을 읽어 비밀 없는 manifest를 export하고 승인된 GET 인수를 시작한다. 파일 export는 원장의 사본이며
   원자성/중복 방지 근거는 DB commit이다. export 실패 때문에 fixture를 다시 생성하지 않는다.

같은 run의 동시 실행은 unique INSERT 대기/중복으로 거절한다. 중복 오류를 받은 transaction은 전체 rollback한 뒤
기존 COMMITTED/CLEANED 원장을 별도 읽기 단계에서 확인한다. scope hash가 일치하면 기존 IDs/상태를 반환하고 신규 INSERT하지 않는다.
다른 승인 scope, 불완전 상태, cleanup 뒤 재생성 요청은 중단한다. commit 이전 실패는 원장과 quiz 모두 없어야 하며,
그것을 확인한 새 승인된 시도만 가능하다. commit 응답을 잃은 경우에도 기존 원장 확인 없이 자동 재생성하지 않는다.

SQLite seal 실패 검증에 더해 실제 V1–V53을 적용한 private MySQL 8.0.43에서 두 connection의 같은 run 경합,
seal 후 connection 종료 rollback, commit 응답 유실 뒤 기존 DB manifest 회수, 제한 grant와 FK graph 검사를 실행했다.
보호 자산에는 SELECT만 필요한 FOR SHARE, 변경할 원장에는 FOR UPDATE를 사용한다.
세부 증거와 설치/역할 분리는 [로컬 adapter 문서](mysql-local-adapter.md)에 있다.
mysqld/OS crash recovery·DEV FK/schema/audit·운영 원장의 존재·실HTTP/인증은 입증하지 않는다.

## 담당·정리·차단 상태

| 책임 | 제안 담당 | 확정 상태 |
| --- | --- | --- |
| 전용 자산 확인·최소 메타 조회·quiz 실행 | BE 한승준 | 미확정; 접근/조회/생성 승인 대기 |
| 원장/schema/adapter 검토·최종 통합 | 기존 BE 통합 담당 세션과 BE 담당 | 담당 역할·구현 범위 승인 미확정; 이 문서가 배포를 승인하지 않음 |
| 본인 인증·복습 화면 인수 | FE 이감재 | 제안/미확정; 실제 인증·GET 인수 승인 대기 |
| manifest 범위 정리 | BE 한승준 | 제안/미확정; 별도 정리/영구삭제 승인 대기 |

선택 경로에서 미래 정리 대상은 CREATED quiz **103개만**이며 REUSED_PROTECTED의 users/materials/sessions는 삭제하지 않는다.
정리 시 run 행 잠금, 정확한 quiz IDs/소유/상태/hash 및 제출/추가 descendants를 다시 검사한다.
추가 데이터가 생기면 자동 cascade하지 않고 중단한다. 승인된 삭제 후 원장/ID 목록과 CLEANED 기록을 보존한다.
로컬 111행 삭제 모의는 전부 CREATED인 SQLite 모델에만 해당하며 이번 재사용 경로의 DEV 삭제 수가 아니다.

현재 실환경 차단 항목은 실제 전용 IDs/기대 version·자료 updated_at 제공·metadata 조회 승인·재사용 판정·인증 readiness·
쓰기 승인·원장 schema 설치/완전한 FK 검사 경로·운영 adapter 승인 및 구현이다. 로컬 adapter 구현/검증은 완료했다.
생성 후 인수/정리 권한과 사용 기간도 확정돼야 한다. 영구삭제 승인은 생성 승인과 분리한다.
parent가 외부 회신/역할 확인을 담당한다. 이 세션은 채널에 비밀을 전달하거나 담당이 정해졌다고 회신하지 않는다.
