# #457 퀴즈 제출 접근권 회수 — 구현·검증 기록

검증일: 2026-10-01 KST. 커밋 전 로컬 구현 검증 시점의 기록이며 GitHub CI·배포 완료 기록이 아니다.

- 대응 이슈 / Finding ID: #457 / `csf_d3f2798117235ad82309dd61`. 입력 지시서는 원 작업 트리의 `docs/issues/security/2026-09-30/03-quiz-access-revocation.md`와 대응 work-order다.
- 작업 브랜치 / 검증한 HEAD / 미커밋 변경 여부: `codex/457-quiz-access-revocation`, 기준 HEAD `a5d4722a0be121532d9af4d6fb0dd08dc4d18255` 위 미커밋 수정. 별도 관리 worktree `C:/Users/russe/.codex/worktrees/457-quiz-access/edupilot`에서 작업했다. 원래 `C:/edupilot`의 #459 브랜치와 기존 미추적 자료는 보존했다. 분석 기준 `6471e168559f6b5af8c4b5a3d06eaf2122dac027`과 현재 HEAD의 대상 퀴즈·자료 접근·강의실·claim 코드에는 작업 시작 시 차이가 없었다. 기준 커밋으로 되돌리지 않았다.
- 변경 요약과 주요 파일: 제출 준비·기존 결과 재응답·저장에 현재 자료 접근권을 적용했다. `QuizSubmissionService`는 채점 직전, 저장 직후 및 반환 직전에 재검사하고 접근 거절을 성공 fallback으로 바꾸지 않는다. `AssessmentPersistenceService`, `DiagnosisPersistenceService`, `LearningSupportPipeline`은 회수 이후 후속 평가·메모리 후보·진단 반영을 차단한다. `MaterialAccessService`와 `ClassroomWeekMaterialRepository`는 저장에 필요한 실제 grant 행을 잠근다.
- 적용한 정책과 근거 / 남은 TBD: [DEC-044](decisions.md#dec-044--퀴즈-제출의-현재-자료-접근권과-회수-경계-457)에 지시서의 저장 전 재검사 권고안, 커밋 선후 관계와 응답 승인 경계를 기록했다. 로컬 정책·코드 경계는 정했고 MySQL 8에서 동일 경합 실행, FE 404 처리, 팀 리뷰·CI는 미확인이다.
- 재현 테스트의 수정 전·후 결과: 초기 7개 DB 통합 테스트 중 수정 전 4개 실패·3개 통과, 수정 후 동일 7개 통과. 실패는 회수 후 신규 제출이 권한 오류 대신 채점 단계까지 도달, 동일 requestId 결과 재응답 허용, AI 대기 중 회수 뒤 저장 허용, 저장 중 멤버십 제거 커밋 허용이었다. 최종 확장 통합 테스트는 13개이며 관련 단위·API·회귀를 합쳐 73개 모두 통과했다.
- 검증표 각 항목의 테스트명과 통과·실패·미실행 결과: 아래 Q1–Q9 표에 테스트명과 확인 경계를 기록했다. 각 항목의 로컬 검증은 통과했으며 DB 경합은 H2에서 실행했다. MySQL 및 실제 HTTP 전송 경합은 미실행이다.
- 실행한 명령과 결과 / GitHub CI 확인 상태: 아래 로컬 검증 기록 참조. 구현 검증 시점에는 커밋·push·PR·배포를 수행하지 않았으며 이 수정의 GitHub CI는 실행·확인하지 않았다. 이후 Git 전달 상태는 브랜치·PR에서 별도 확인한다.
- API·FE·DB·운영 영향 및 갱신 문서: 응답 구조·스키마·마이그레이션·환경 변수 변경 없음. 현재 접근권 없는 제출/재시도는 404로 거절되므로 FE는 결과 표시·자동 재제출을 중단해야 한다. OpenAPI는 `QuizController`의 Operation 설명을 갱신했다. [API](api-spec.md), [화면 매핑](screen-api-map.md), [오류](error-code.md), [도메인](domain-model.md), [결정](decisions.md)을 함께 갱신했다. 모델·문항 수·AI 학습 판단·토큰 상한은 변경하지 않았다.
- 남은 작업과 완료 조건 충족 여부: 구현·재현·문서 갱신·대상 검사·전체 build까지 로컬 작업은 완료했다. 원 이슈의 필수 CI·PR 검증 기록·리뷰까지 포함한 전체 완료 조건은 아직 충족하지 않았으며, 로컬 성공을 원격 완료로 표시하지 않는다.

## 접근 검사를 적용한 경계

| 경계 | 구현 | 보장 |
| --- | --- | --- |
| 제출 준비 | `QuizSubmissionPreparationService.prepare` | 기존 quiz 소유권 검증 후 `assertSessionAccessible`; private questions getter·페이지 문맥 사용 전에 거절 |
| 채점 직전 | `QuizSubmissionService.submit` | claim 이후 재조회가 끝난 뒤 현재 권한 재검사; 이 시점에 회수가 확인되면 AI 호출 없음 |
| 결과 재응답 | `QuizSubmissionPersistenceService.findByRequest` | 실제 결과 복원 전 공통 검사. 최초 재요청·claim 이후 재조회·DataIntegrityViolation 복구 모두 보호 |
| 다른 requestId의 중복 제출 | `QuizSubmissionPersistenceService.exists` | 기존 제출도 현재 권한을 확인한 뒤 중복 여부 판정 |
| 결과 상세 | `findDetail` | 기존 접근권 정책 유지. 준비·결과 조회는 READ_COMMITTED 사용 |
| 제출 저장 | `persist` → `requireAccessibleForUpdate` | 세션·자료·grant 잠금을 보유한 짧은 트랜잭션에서 검사 후 저장·진행 변경 |
| 후속 처리 | `LearningSupportPipeline.onGraded`, 평가·진단 persistence | 새 단계 시작 전 권한 확인. 외부 응답 저장 전 같은 잠금 정책으로 검사. assessment·메모리 후보·진단·pending 상태 저장 차단 |
| 반환 | 저장 직후/파이프라인 종료 후 `assertSessionAccessible` | 회수된 결과를 일반 성공·fallback 성공으로 반환하지 않음. 이전 페이지 퀴즈의 조기 반환도 저장 직후 검사 적용 |

본인 소유권과 현재 자료 접근권은 별도 조건이다. 자료 소유권이나 다른 강의실 grant가 남으면 허용한다. 과거 기록을 일괄 삭제하거나 기존 기록 목록 정책을 변경하지 않는다.

## 오류·동시성·claim 정책

- 자료 접근 거절: 기존 상세 조회와 같은 **404 `MATERIAL_NOT_FOUND`**. 오류 응답에 보호된 `data`, feedback, 정답이 포함되지 않는 API 계약 테스트를 통과했다. 타인 퀴즈는 기존 404 `QUIZ_NOT_FOUND`를 유지한다.
- 회수 우선: 멤버십 삭제가 commit된 뒤 채점 결과를 저장하려 하면 거절한다. 삭제 트랜잭션이 진행 중이면 저장의 grant locking read가 기다린 다음 실제 commit 결과로 판단한다.
- 저장 우선: 저장 트랜잭션이 자료·grant 잠금을 먼저 확보하면 멤버십 삭제는 저장의 commit/rollback까지 기다린다. 이미 커밋한 제출은 남고, 회수 이후 재요청·상세 조회는 거절한다.
- 저장 직후 회수: 후속 처리 시작 전 검사에서 확인되면 hook을 실행하지 않는다. 후속 처리 중 회수돼도 늦게 도착한 평가·메모리 후보·진단은 저장하지 않는다. 최종 검사에서 회수됐으면 제출이 이미 저장됐어도 404를 반환한다.
- 응답 승인 경계: 반환 직전 권한 검사를 통과한 결과의 HTTP 직렬화·네트워크 전송까지 잠금을 유지하지 않는다. 그 검사 이후 회수는 이미 승인한 응답을 소급 취소하지 않으며 이후 요청을 막는다. AI 호출도 직전 검사 이후 이미 시작된 호출을 취소한다고 보장하지 않고, 저장 경계에서 폐기한다.
- claim: 준비에서 거절되면 claim 없음. claim 획득 이후 접근 거절·AI 오류·중복 저장 복구·후속 예외는 finally로 해제한다. DB 통합 테스트에서 `active_turn_request_id IS NULL`을 확인했다.
- 잠금은 자료뿐 아니라 현재 접근 근거인 membership·week·material-link 행에 적용한다. 단순 SELECT 재검사만으로 삭제와의 경합을 막았다고 주장하지 않는다. [MySQL locking read 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)를 근거로 구현했고, 실행 검증 DB는 H2 2.4.240 MySQL mode다. 로컬 Docker engine이 실행 중이지 않아 MySQL container 검증은 하지 않았다.

## Q1–Q9 실행 결과

아래 `Jpa`는 새 `QuizSubmissionAccessJpaTest`, `Service`는 `QuizSubmissionServiceTest`를 뜻한다. 모두 로컬 실행 결과다.

| ID | 테스트명 | 결과·확인 경계 |
| --- | --- | --- |
| Q1 | Jpa.`revokedLastMembershipRejectsNewSubmissionWithoutChanges` (OX/SHORT), `QuizSubmissionPreparationServiceTest.revokedAccessIsRejectedBeforePrivateQuestionsAndPageContextAreUsed` | **통과**. 실제 멤버십 삭제, 채점 호출·제출·진행·메모리 후보 저장 없음 |
| Q2 | Jpa.`revokedMembershipRejectsReplayAndDetailButPreservesRecord`, `QuizSubmissionPersistenceServiceTest.revokedReplayIsRejectedBeforeProtectedResultReconstruction` | **통과**. 같은/다른 requestId와 상세 조회 거절, 기존 제출 행 보존 |
| Q3 | Service.`deniedReplayAfterClaimStopsGradingAndReleasesClaim`, `deniedDuplicateInsertRecoveryDoesNotReturnProtectedResult` | **통과**. claim 이후·중복 저장 예외 복구 분기에서 공통 finder의 권한 예외 전파, claim 해제. 분기 제어는 mock, 실제 finder 차단은 Q2 DB 테스트로 검증 |
| Q4 | Jpa.`materialOwnerAndAlternativeClassroomGrantRemainAllowed`, `MaterialAccessServiceTest.writeAccessLocksCurrentGrantsAndStillAllowsOwner` | **통과**. 자료 소유자 및 별도 강의실 연결을 실제 저장한 대체 접근권 사례 제출·조회 허용 |
| Q5 | Jpa.`anotherUsersQuizRemainsHidden`, `QuizApiContractTest.submitUsesDocumentedEnvelopeAndHiddenOwnershipError` | **통과**. 본인 소유권 유지, 타인 quizId는 QUIZ_NOT_FOUND |
| Q6 | Jpa.`concurrentAuthorizedRequestsStoreOnceAndReplaySameResult`, Service.`sameRequestReplaysStoredResultWithoutClaimOrGrading` | **통과**. AI stub 대기 중 동시 요청은 claim 충돌, 저장 1행·AI 채점 1회, 완료 뒤 같은 결과 재응답 |
| Q7 | Jpa.`revocationCommittedWhileAiWaitsRejectsPersistenceAndReleasesClaim` | **통과**. AI stub의 트랜잭션 없음 확인, 대기 중 실제 회수 commit, 반환된 채점 폐기·저장/진행/후속 처리 없음·claim 해제 |
| Q8 | Jpa.`savingTransactionOrdersMembershipRemovalAfterCommit`, `removalTransactionWinningRaceRejectsWaitingSave`, `revocationAfterSaveSuppressesResponseAndPreservesCommittedHistory`, `revokedAccessRejectsLateAssessmentMemoryAndDiagnosisWrites`; Service.`revocationImmediatelyAfterSaveSkipsPostGradingAndResponse` | **통과(H2)**. 양쪽 잠금 순서·저장 직후 회수·응답 억제·기존 기록 보존·늦은 후속 저장 차단. MySQL 실행·HTTP 전송 경합까지 검증한 것은 아님 |
| Q9 | Jpa.`gradingFailureReleasesActualClaimWithoutSaving`; Service.`postGradingAccessDenialIsNotDowngradedToSuccessfulFallback`, `fallbackResponseStillChecksAccessAfterOtherPipelineFailure`; `LearningSupportPipelineTest.assessmentAccessDenialStopsDiagnosisInsteadOfReturningFallback` | **통과**. 채점/접근 예외 후 claim 해제, 광범위한 catch가 보호 데이터를 fallback으로 반환하지 않음 |

추가 통과: Jpa.`lastMaterialLinkRemovalAlsoRejectsPreparedSubmission`, `QuizApiContractTest.revokedMaterialAccessUses404ForSubmitReplayAndDetailWithoutFeedback`, 기존 ClassroomStudent·QuizSubmission·LearningSupport 회귀.

## 실행 명령과 증거

Java 21.0.9, 저장소 Gradle wrapper 9.5.1. 작업 worktree의 `main-service`에서 실행했다. 아래 Git Bash 명령과 동등한 `gradlew.bat` 명령을 PowerShell에서 사용했다.

```bash
./gradlew test --tests 'io.edupilot.quiz.QuizSubmissionAccessJpaTest' --no-daemon
./gradlew test --tests 'io.edupilot.quiz.QuizSubmission*Test' \
  --tests 'io.edupilot.quiz.QuizApiContractTest' \
  --tests 'io.edupilot.material.MaterialAccessServiceTest' \
  --tests 'io.edupilot.classroom.ClassroomStudent*Test' \
  --tests 'io.edupilot.assessment.LearningSupport*Test' --no-daemon
./gradlew build --no-daemon
git diff --check
```

- 재현: 수정 전 7개 중 4개 실패, 동일 테스트 수정 후 7개 통과. 최종 확대된 DB 통합 테스트 13개 포함 대상 **73 tests / 0 failures / 0 errors / 0 skipped**.
- 전체 build: **BUILD SUCCESSFUL**, 4분 41초. 226개 suite의 **1,233 tests / 0 failures / 0 errors / 1 skipped** — 1,232개 통과. 기존 `AiClientLiveTest.liveHealthTurnAndInvalidTokenContract`는 `it.ai=true`를 지정하지 않아 제외됐다. 실제 AI 서비스에 연결하는 opt-in 검증을 성공으로 계산하지 않았다.
- `git diff --check`: 통과. CRLF 전환 안내 외 공백 오류 없음.
- 증거 파일(생성 산출물, commit 대상 아님): `main-service/build/quiz-access-red.log`, `quiz-access-red-results.xml`, `quiz-access-green.log`, `quiz-access-targeted.log`, `quiz-access-targeted-results.json`, `quiz-access-build.log`, JUnit `build/test-results/test/TEST-*.xml`.
- 초기 offline 시도는 별도 Gradle 캐시의 의존성 누락으로 compile 전에 실패했다. 기본 캐시에서 선언된 의존성을 복원해 실행했으며 dependency 버전 변경은 없다. 첫 fixture의 중복 storage key와 확장 테스트의 잘못된 FK 컬럼명은 테스트 작성 오류로 수정했다. 이를 제품 취약점의 재현 실패와 구분한다.
- 실제 Google·Grok·운영 DB 호출 없음. 합성 답안·AI stub, H2와 독립 DB 트랜잭션만 사용했다.
- GitHub CI·PR·dev/운영 배포·브라우저 FE 검증: **미실행**.

## 남은 전달 사항

1. 로컬 변경 리뷰 후 별도 요청에 따라 커밋·push·PR을 진행하고 필수 CI를 확인한다.
2. 배포 전 dev MySQL에서 같은 grant 삭제·저장 경합을 재검증한다. 자료·week·membership 잠금은 짧은 DB 단계에만 존재하지만 실제 DB 쿼리 계획·잠금 범위는 별도 확인 대상이다.
3. FE는 제출·같은 requestId 재시도·결과 조회의 MATERIAL_NOT_FOUND를 접근권 회수로 처리하고 보호된 결과 표시와 자동 재제출을 중단해야 한다.
