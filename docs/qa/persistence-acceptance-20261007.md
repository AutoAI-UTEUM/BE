# Persistence acceptance — 2026-10-07

## 기준과 범위

- 원요청 `1555840753727447183`: draft 204/409, 저장 답안 고정 regrade, clientId 중복 방지의 문서 확인을 넘어 실제 저장·충돌·실패 복구·다른 기기 복원의 검사 근거를 보완한다.
- 기준: [PR #533](https://github.com/AutoAI-UTEUM/BE/pull/533)의 exact head `28e265397dc52667fd5e7f98f1e782ba2ddafef7`.
- 브랜치: `feature/persistence-acceptance-20261007`. PR #533 위에 쌓는 테스트·문서 전용 변경이다.
- 소유 파일: `PersistenceAcceptanceJpaTest.java`, `PersistenceAcceptanceMigrationTest.java`, 이 문서. 기존 runtime·migration·설정·공유 테스트는 수정하지 않는다.
- 합성 사용자와 답안만 사용한다. H2 in-memory 두 데이터베이스와 mock AI를 사용하며 서버·메일·유료 AI·운영 데이터·실계정·권한·DEV/PROD 배포에는 접근하지 않는다.

## 계약과 기존 근거 대조

실행 계약의 정본은 [API 명세](../api-spec.md)의 수동 노트 §5.2 및 학생 시험 API와 아래 runtime이다. 기존 기능을 다시 구현할 필요는 발견하지 않았다.

| 계약 | 실제 구현 및 기존 검사 | 이번에 추가한 근거 |
| --- | --- | --- |
| draft 최초 저장, 버전 증가, 없으면 204 | [DraftService](../../main-service/src/main/java/io/edupilot/exam/ExamAttemptDraftService.java), [Controller](../../main-service/src/main/java/io/edupilot/exam/ExamAttemptDraftController.java); `ExamAttemptDraftJpaTest`, `ExamApiContractTest` | 커밋 뒤 실제 JSON·한글·줄바꿈·인용·이모지·savedAt을 독립 JWT 조회로 대조하고, 같은 강의실의 다른 학습자에게는 204 |
| draft 충돌 409 + 최신 답안, 자동 병합 없음 | [Repository](../../main-service/src/main/java/io/edupilot/exam/ExamAttemptDraftRepository.java)의 조건부 UPDATE 및 시험×사용자 UNIQUE; 기존 버전 충돌·동시 UPDATE 검사 | 내용이 다른 두 최초 PUT의 200/409와 DB 승자 내용 일치; 오래된 버전 실패 뒤 최신 버전으로 전체 스냅샷 교체, 생략 문항 제거 |
| 최종 제출 성공 때만 draft·응시 시작 소비, 제출 본문이 정본 | [SubmissionPersistenceService](../../main-service/src/main/java/io/edupilot/exam/ExamSubmissionPersistenceService.java)의 단일 트랜잭션; `ExamFailureSecurityJpaTest.rejectedRestoredDraftPreservesStartAndDraftButValidBodyWins` | draft 삭제·응시 시작 삭제·제출 INSERT·답안 flush 뒤 실패를 주입해 전부 롤백되는지 확인; 같은 requestId 재시도에 제출 본문 저장, 후속 다른 본문 재전송도 원래 답안 유지 |
| 학생 regrade는 본문 없이 저장 답안으로 동일 제출 복구 | [StudentExamService](../../main-service/src/main/java/io/edupilot/exam/StudentExamService.java), 기존 `ExamFailureSecurityJpaTest`·`ExamGradingRecoveryJpaTest` | mock AI timeout 뒤 독립 JWT로 본문 없는 재채점 202/반복 202/완료 200; submission·answer 행 ID, requestId, attemptNo, 제출/응시 시각·duration을 SQL로 전후 대조 |
| user-notes 신규 201, 동일 사용자 clientId 재전송 200 | [UserNoteService](../../main-service/src/main/java/io/edupilot/usernote/UserNoteService.java)의 사용자 행 잠금·`REQUIRES_NEW`; `UserNoteJpaTest` | 서로 다른 본문의 동시 create가 같은 ID·내용 1건으로 수렴; 다른 사용자에게 같은 키 허용; INSERT flush 후 실패는 행 0건, 같은 키 재시도 201, 다른 본문 재전송은 기존 내용 200 |
| user-notes 수정은 저장 후 서버에서 복원 | 동일 서비스의 update 트랜잭션, 기존 CRUD·소유권 검사 | PATCH flush 후 실패가 기존 content·updatedAt을 보존; 정상 PATCH 뒤 독립 JWT 상세·목록에서 정확한 한글·줄바꿈·이모지 복원, 타인 상세 404 |
| clientId는 DB 유일 제약으로도 보장 | [V46](../../main-service/src/main/resources/db/migration/V46__user_notes_wrong_answer_notes.sql), 기존 `UserNoteMigrationTest`는 nullable 키·자료 삭제·오답 결과 키 중심 | 실제 V46 SQL을 H2에 적용해 동일 사용자 clientId 중복 거부, 소프트 삭제 후에도 키 보존, 다른 사용자와 여러 null 키 허용 |

기존 검사 목록은 기준 SHA에서 **읽어 대조한 근거**다. 이번 실행에서 해당 기존 클래스 전체를 재실행했다고 간주하지 않는다.

## 추가 합성 회귀

[PersistenceAcceptanceJpaTest](../../main-service/src/test/java/io/edupilot/exam/PersistenceAcceptanceJpaTest.java)는 test 메서드 외부 트랜잭션을 두지 않는다. 서비스 프록시의 commit/rollback 뒤 JDBC·Repository·MockMvc로 다시 읽는다. H2 URL을 검사하여 다른 DB로 실행되지 않게 한다. `AiClient`, 자동 채점 dispatcher와 recovery scheduler는 mock이며, 채점 worker는 테스트가 직접 호출한다.

| 메서드 | 확인 목적 |
| --- | --- |
| `committedDraftRestoresExactContentWithIndependentTokenAndIsPrivate` | 서버 저장 내용과 독립 인증 조회·소유권 |
| `staleDraftGetsLatestCommittedAnswerAndResolutionReplacesTheWholeSnapshot` | 오래된 버전 거부·명시적 충돌 해결·전체 교체 |
| `concurrentFirstDraftCreatesReturnOneWinnerAndTheSameWinnerIn409` | 최초 생성 경합에서도 승자 1행·정확한 최신 답안 |
| `failedFinalWriteRollsBackDeletedDraftStartSubmissionAndAnswersThenRetryUsesBody` | 실제 flush 후 실패·원자적 롤백·멱등 재시도 |
| `failedNoteInsertRollsBackAndSameClientIdRetryCreatesOnceWithoutOverwriting` | 노트 INSERT 롤백·같은 키 복구·응답 유실 재전송 |
| `concurrentNoteClientIdRetriesKeepOneWholeContentAndAreScopedToTheOwner` | clientId 동시 재전송·사용자별 키 범위 |
| `failedNotePatchRollsBackAndSuccessfulPatchRestoresWithIndependentToken` | 수정 롤백·정상 저장·독립 인증 상세/목록 복원 |
| `bodylessRegradeOnAnotherTokenPreservesStoredAnswerIdsRequestAndAttemptTiming` | timeout 복구·저장 답안/행/시각 고정·중복 dispatch 방지 |

[PersistenceAcceptanceMigrationTest](../../main-service/src/test/java/io/edupilot/usernote/PersistenceAcceptanceMigrationTest.java)의 `clientIdUniquenessIsPerOwnerAndStillAppliesAfterSoftDelete`는 V46 원문 SQL을 실행한다. JpaTest의 Hibernate 생성 스키마에는 UserNote의 migration-only UNIQUE가 없으므로 그 클래스에만 H2 등가 인덱스를 명시 설치한다. migration 검사와 runtime 검사를 구분하며, 두 검사 모두 MySQL/Flyway 실인수를 대신하지 않는다.

실패 주입은 실제 repository 호출·flush가 반환된 다음 `DataAccessResourceFailureException`을 한 번 발생시킨다. 서비스가 성공 응답을 반환하기 전 실패했을 때 rollback과 재시도가 보장되는지를 검증한다. DB 프로세스 종료, 디스크 장애 또는 네트워크 응답 유실 자체를 발생시킨 검사는 아니다. 성공 뒤 응답 유실은 동일 key/requestId 재전송으로 모사한다.

## 실행 및 결과

- JDK 21, Gradle 9.5.1, offline, 단일 worker, 전용 Gradle cache·worktree build 사용.
- 기존 공유 캐시의 `modules-2`를 읽어 전용 캐시로 복사했다. 공유 캐시/원본 작업트리에 빌드 산출물을 쓰지 않는다.
- sandbox 실행 두 번은 JDK ZIP close의 의존성 JAR `AccessDeniedException`으로 `compileJava`에서 중단되었다. 테스트 결과로 집계하지 않는다. 동일 격리 offline 명령의 권한 검토 후 실행했다.
- 최초 테스트 실행의 3건은 인터페이스 spy의 `callRealMethod()` 오류였다. 실제 Spring Data repository로 위임하는 테스트 실패 주입으로 수정했다. runtime 결함으로 분류하지 않는다.
- 최종 집중 실행: **BUILD SUCCESSFUL, 9/9 PASS, failures=0, errors=0, skipped=0**, Gradle 1m10s. `PersistenceAcceptanceJpaTest` 8건(33.478s), `PersistenceAcceptanceMigrationTest` 1건(1.568s). XML의 클래스·건수·실패·skip을 직접 대조했다.
- 로컬 보고서: `main-service/build/reports/tests/test/index.html`와 `main-service/build/test-results/test/TEST-io.edupilot.*.xml`(생성 산출물, Git 제외). 기존 runtime/build 파일 diff 없음, 원본 BE 작업트리 깨끗함, 새 문서 상대 링크 10/10 정상.

| 검사 파일 | 검증된 source SHA-256 | 로컬 XML SHA-256 |
| --- | --- | --- |
| `PersistenceAcceptanceJpaTest.java` | `31150909776f9221cdc1b5c6d6d271700eb3d0415d52e5215b0ffccc8ed27b90` | `d09d53a815f8922ed180c166bd1113707234a10a3a400345ff2d67a42e5b4405` |
| `PersistenceAcceptanceMigrationTest.java` | `52ccd506dfae2c2bd4bc7da402447479efb1c599aaf6d7d8d905d2fd1ce26a08` | `6f0c6b84a87e17c52d4bed6d1181d6a1ccc8767d31111e018e75d55aec084057` |

실제 집중 검사 명령(PowerShell, `main-service`에서):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:GRADLE_USER_HOME = 'C:\Users\russe\Documents\Codex\2026-10-07\task\pag'
& 'C:\Users\russe\.gradle\wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1\bin\gradle.bat' --offline --no-daemon --max-workers=1 --console=plain test --tests '*PersistenceAcceptance*'
```

전체 Gradle build/test와 새 MySQL 인스턴스는 실행하지 않는다. 필수 CI는 게시된 최종 head 결과를 별도로 확인한다.

## 인수 단계와 남은 조건

| 단계 | 증거/상태 |
| --- | --- |
| 코드 구현 | 기준 SHA의 기존 저장·충돌·복구 runtime 확인. 새 기능·runtime 변경 없음 |
| 합성 검사 | 로컬 H2·인증 MockMvc·mock AI·실패 주입. 집중 검사 최종 결과는 위 절 참조 |
| DEV 실제 인수 | 미실행. 실제 FE와 독립 기기/브라우저, MySQL migration/commit, 새로고침·재로그인·응답 유실·오프라인 재접속의 캡처·DB 집계가 필요 |
| PROD | 미실행. merge·배포 승인과 대상 SHA·image·migration/backup/restore 근거가 별도로 필요 |

DEV 합성 인수에서는 동일 계정의 기기 A가 draft v1을 저장한 뒤 기기 B에서 복원하고, 양쪽 변경 중 한쪽의 오래된 버전에 409+실제 최신 답안이 반환되는지 확인한다. FE가 사용자 선택으로 해결한 버전을 다시 저장하고 새로고침 후 복원하는지 기록한다. 저장 응답을 잃은 최초 PUT은 성공 여부를 GET으로 확인해야 하며, version 0을 재전송하면 409가 정상이다. 최종 제출 실패 후 draft·시작 기록이 남고 재시도 성공 후에만 소모되는지, 채점 실패에서 기존 저장 답안을 본문 없는 regrade로 복구하는지도 확인한다.

노트에서는 생성 성공 응답 유실 후 같은 clientId 재전송이 같은 ID·원래 내용을 반환하는지, 다른 기기의 상세·목록이 수정 내용을 복원하는지 확인한다. 삭제한 노트의 clientId를 새 노트에 재사용하지 않는다. 현행 PATCH에는 version/ETag가 없으므로 같은 기존 노트의 동시 편집을 draft처럼 409로 해결하는 계약은 없다. 이번 clientId 검사는 **생성 중복 방지** 근거다. 동시 노트 편집의 충돌 감지가 제품 요구라면 FE/BE의 version/If-Match 계약을 먼저 확정할 후속 조건으로 남긴다.

독립 JWT 검사는 backend의 계정 기준 복원을 검증한다. 실제 두 기기의 FE 저장소, refresh/auth-session, 브라우저 통신 및 서버 재시작 복원까지 검증한 것으로 표시하지 않는다.
