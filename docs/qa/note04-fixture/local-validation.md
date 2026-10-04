# NOTE04 로컬 검증 기록

검증일: 2026-10-04 (UTC). 고정 base:
`ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938` (`origin/develop`, PR486의 후손).
브랜치: `feature/479-note04-local-fixture`.
실행기: Windows, Node `v24.12.0`, 내장 SQLite `3.50.4`, `:memory:` 합성 DB.
실제 DEV/서버/DB 접속 0회; 실AI/메일/SMS 호출 0회; 계정·동의 생성과 credentials 공유 0회.

## 명령과 결과

```text
node --disable-warning=ExperimentalWarning --test scripts/qa/note04-fixture/fixture.test.mjs
node --check scripts/qa/note04-fixture/fixture.mjs
node --check scripts/qa/note04-fixture/fixture.test.mjs
git diff --check
```

초기 head `30add0070c3d9f2117074e2d650d74e24b7dbaa8`의 독립 실행 결과는 **30/30 pass**였다.
FE 후속 요청 보완에서는 원장 seal 이후 실패 rollback 1개와 실제 FE 소비자 mock 9개를 추가했다.
두 스크립트 문법 검사 통과. staging 후 최종 diff의 whitespace 검사도 통과했다.
`--disable-warning=ExperimentalWarning`은 SQLite 안내 경고만 숨긴다.
초기 source의 Git blob은 `fixture.mjs` `7b09d954e4106a5fe1af67a81b3dc39ff0181d78`,
`fixture.test.mjs` `563bb39133b5a1e339142de89b0fee8985284caf`였다. 보완 후 결과는 아래에 별도 기록한다.
추가 직접 CLI `--mode local-rehearsal`에서 100/1/0, 두 sentinel 1/1,
111행 정리 dry-run rollback/대상 외 보존을 확인했다. 상대 Markdown 링크 8개도 존재 검사에 통과했다.

| 범위 | 확인 결과 |
| --- | --- |
| fixture/manifest | 학습자 2, 자료 3, 세션 3, 정확한 OX ID 103; A1=101/A2=1/B1=1; 실환경 ID 아님; 승인 flag 전부 false |
| 정렬/응답 모델 | A1의 같은 created_at, quizId DESC, 미제출/null 점수, PDF page7; page0/1/2의 100/1/0와 101/2 메타 |
| 격리/범위 | sentinel 혼합 0, A→B1/B→A1·A2/없는 세션 404 모델, 삭제 세션 404 모델, page/size 400 모델 |
| 재조회 | 같은 page1 재조회 시 동일 응답/ID, DB 행 변경 0; FE retry 코드 검증은 아님 |
| 기본 dry-run | seed 후 전체 rollback, observer 포함 전체 snapshot 동일, dry-run manifest 정리 거절 |
| 실패 rollback | users/materials/sessions/quizzes·manifest 원장 seal 직후 실패 및 대상 외 변경 주입; 전체 생성/원장 transaction rollback |
| 중복 방지 | 동일 메모리 DB의 같은 run/manifest 거절, 정리 후 tombstone으로 재생성 거절 |
| 정리 모의 | 정확한 manifest IDs의 111행 삭제 후 rollback; 중간 삭제 실패 rollback; 로컬 확인 없으면 commit 거절 |
| 정리 성공 모델 | LOCAL_MEMORY_ONLY 확인 하에 메모리 행만 정리, 대상 외 행 유지, 원장 보존; 실제 삭제 미실행 |
| 조건 불일치 | manifest ID 범위 확대/소유관계 조작, 소유권/자료상태/행수/created_at/quiz row image/제출/연관행/observer 변경 시 정리 거절; 시도 전 snapshot 보존 |
| 오용 방지 | 파일 target/factory 인자, 외부 connection, ATTACH, FK 비활성화, dev/apply/DB URL/token/중복 mode 거절 |
| 비밀/환경 | dummy DB 환경 변수에도 메모리 dry-run만 실행, token 인자 오류에 값 미출력, manifest/응답에 비공개 답안·credentials 없음 |

`networkCalls=0`은 실행기에 network 호출 코드/target 입력이 없다는 로컬 범위를 표현한다.
네트워크 packet capture나 DEV 관찰 보고서가 아니다. 테스트의 dummy 변수/값은 합성 문자열이며
실 credentials를 조회하거나 전파하는 작업이 아니다.

## 검증 한계와 기존 검증의 재사용

이 SQLite schema는 `fixture_*` 이름의 독립 모델이다. 실제 MySQL/Flyway/auth/storage의 전체 필수 컬럼,
locking/isolation, 동시 실행, process 간 원장, FK/cascade, 실제 Spring serialization/권한을 실행한 것이 아니다.
로컬 cleanup 행수 111과 숫자 IDs를 DEV 계획의 삭제 대상으로 복사하지 않는다.
React 렌더·브라우저의 500/429/retry/dedup/abort UI 인수는 실행하지 않았다.

## FE224 후속 정정의 오프라인 검증

FE develop `1b6987d8472a064a080a00bd43232993e9bd41eb`를 별도 읽기 전용 clone에서 확인했다.
PR224의 자동 페이지 조회, 전체 세션 성공 반환, 성공 peer 보존, 실패 세션 page0 재시작을 반영했다.
BE의 `fixture_*` 외부환경 접속 기능을 추가하지 않았다. 신규 `fe-consumer.test.mjs`만 추가했다.

```powershell
$env:NOTE04_FE_SOURCE_ROOT = 'C:\path\to\FE-readonly'
node --disable-warning=ExperimentalWarning --test scripts/qa/note04-fixture/fixture.test.mjs scripts/qa/note04-fixture/fe-consumer.test.mjs
```

FE의 실제 TypeScript repository와 error class를 Node `stripTypeScriptTypes`로 읽고 HTTP/auth imports를 제거한다.
실제 collection의 useEffect callback은 JSX 밖에서 추출해 실행하며 `void load()`를 관찰용 `onLoad(load())`로만 바꾼다.
실제 정렬 helper도 실행한다. `AuthenticatedRequest`는 합성 SQLite 응답/가짜 오류만 반환한다.
FE 파일을 수정하거나 실제 auth/HTTP client·React runtime·socket·provider를 실행하지 않는다.
고정 FE head와 source clean 상태를 확인하며 Git trust는 선택한 읽기 전용 경로에만 command별 적용한다.
첫 준비 실행의 Windows Git ownership/TypeScript annotation 오류를 이 harness에서 해결했고 전역 Git 설정은 변경하지 않았다.

소비자 9/9 pass: query 없는 page0→page1 자동 조회/page2 미요청, 초기 부분 결과 미반영,
A 최종 102개, 모의 500/429의 A1 부분 폐기+A2 보존+실패 A1만 page0 재시작, 계정 교체/abort 후 늦은 응답 미반영,
quizId dedup, legacy 단일 queryless 호환, 같은 시각의 큰 정수 문자열 ID 정렬.
증거 분류는 `LOCAL_FE_CONSUMER_MOCK`다. 콜백 동작을 확인했으며 React mount/remount·렌더/UI·실인증 인수를 입증하지 않는다.
보완 최종 합산 실행은 **40/40 pass, failure0/skip0**, 약 0.64초였다(합성 DB 31 + FE source/mock 9).
세 스크립트 문법 검사와 staged whitespace·상대 문서 링크 검사도 통과했다.
검증한 보완 source의 Git blob:

- `fixture.mjs`: `1d8318903d27abc553188b409be85e8c96b81095`
- `fixture.test.mjs`: `974f004ccab5d32e252337e3d9f6436a8403e43b`
- `fe-consumer.test.mjs`: `74b40d9aeb7661d36e186b78836ede139c8bb317`

기존 PR481 댓글에는 `QuizSubmissionAccessJpaTest.paginationIncludesRecordsBeyondOneHundredWithStableTieOrdering`
및 `QuizApiContractTest`의 합성 H2/MockMvc 검증 결과가 있다. 이 작업은 그 설계/기대값을 재사용했다.
기존 테스트 파일을 수정하거나 그 결과를 이번 exact head의 재실행으로 주장하지 않는다.
전체 Gradle/MySQL/Docker 검증은 기존 통합 세션과 자원 조율 없이 실행하지 않았고 main MySQL 서비스를 건드리지 않았다.
현재 런타임 변경이 없으므로 이 작업의 검증 범위는 전용 코드/계획 문서다.
기존 CI는 이 전용 Node 검사를 자동 실행하지 않는다. Main CI가 변경 감지에 따라 service build를
skip한 경우 그 성공을 Gradle 재실행으로 표시하지 않고 로컬 31+9개 결과와 구분한다.

남은 항목: DEV 메타조회/쓰기/계정·인증·동의·정리 승인, 실제 ID/배포 SHA 확정, 별도 auth handoff 문서 연결,
승인된 실응답 및 FE UI mock case 실행, 공유 DB 원장/adapter 승인·구현. develop/main push·merge·배포와 영구삭제는 이 작업에서 실행하지 않는다.
