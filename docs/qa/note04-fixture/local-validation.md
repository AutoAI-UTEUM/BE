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

첫 독립 실행 결과: **30 tests / 30 pass / 0 fail / 0 skipped** (약 0.56초).
두 스크립트 문법 검사 통과. staging 후 최종 diff의 whitespace 검사도 통과했다.
`--disable-warning=ExperimentalWarning`은 SQLite 안내 경고만 숨긴다.
검증한 source의 Git blob: `fixture.mjs`는 `7b09d954e4106a5fe1af67a81b3dc39ff0181d78`,
`fixture.test.mjs`는 `563bb39133b5a1e339142de89b0fee8985284caf`다.
추가 직접 CLI `--mode local-rehearsal`에서 100/1/0, 두 sentinel 1/1,
111행 정리 dry-run rollback/대상 외 보존을 확인했다. 상대 Markdown 링크 8개도 존재 검사에 통과했다.

| 범위 | 확인 결과 |
| --- | --- |
| fixture/manifest | 학습자 2, 자료 3, 세션 3, 정확한 OX ID 103; A1=101/A2=1/B1=1; 실환경 ID 아님; 승인 flag 전부 false |
| 정렬/응답 모델 | A1의 같은 created_at, quizId DESC, 미제출/null 점수, PDF page7; page0/1/2의 100/1/0와 101/2 메타 |
| 격리/범위 | sentinel 혼합 0, A→B1/B→A1·A2/없는 세션 404 모델, 삭제 세션 404 모델, page/size 400 모델 |
| 재조회 | 같은 page1 재조회 시 동일 응답/ID, DB 행 변경 0; FE retry 코드 검증은 아님 |
| 기본 dry-run | seed 후 전체 rollback, observer 포함 전체 snapshot 동일, dry-run manifest 정리 거절 |
| 실패 rollback | users/materials/sessions/quizzes 단계 중간 실패 및 대상 외 변경을 주입; 전체 생성 transaction rollback |
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
FE 브라우저/앱의 500/429/retry/dedup/abort도 실행하지 않았다.

기존 PR481 댓글에는 `QuizSubmissionAccessJpaTest.paginationIncludesRecordsBeyondOneHundredWithStableTieOrdering`
및 `QuizApiContractTest`의 합성 H2/MockMvc 검증 결과가 있다. 이 작업은 그 설계/기대값을 재사용했다.
기존 테스트 파일을 수정하거나 그 결과를 이번 exact head의 재실행으로 주장하지 않는다.
전체 Gradle/MySQL/Docker 검증은 기존 통합 세션과 자원 조율 없이 실행하지 않았고 main MySQL 서비스를 건드리지 않았다.
현재 런타임 변경이 없으므로 이 작업의 검증 범위는 전용 코드/계획 문서다.
기존 CI는 이 전용 Node 검사를 자동 실행하지 않는다. Main CI가 변경 감지에 따라 service build를
skip한 경우 그 성공을 Gradle 재실행으로 표시하지 않고 로컬 30개 결과와 구분한다.

남은 항목: DEV 메타조회/쓰기/계정·인증·동의·정리 승인, 실제 ID/배포 SHA 확정, 별도 auth handoff 문서 연결,
승인된 실응답 및 FE mock case 실행. develop/main push·merge·배포와 영구삭제는 이 작업에서 실행하지 않는다.
