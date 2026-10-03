# 탈퇴·자료삭제 원장과 복원 재적용 (#477)

상태: 구현 검증 중. 보존 일수·정책 버전은 미정이며 물리삭제 기본값은 비활성화다. 이 문서는 실제 운영 백업 복원이나 삭제 완료 증거가 아니다.

## 저장과 상태

자료 삭제와 소유 자료 탈퇴 삭제는 동일 DB 트랜잭션에서 원본 PDF, 렌더 이미지 묶음, 연결된 외부 AI 파일을 각각 `deletion_intents`에 기록한다. 사용자 탈퇴는 원래 이메일의 SHA-256, 가입 시각, 사용자 ID로 계정 재적용 식별자를 기록하고 아바타 정리도 기록한다. 원장 저장 실패 시 논리삭제도 롤백한다. 원문 이메일·PDF·토큰은 원장에 넣지 않는다. 해시와 파일 식별자도 운영 접근이 제한되는 데이터다.

파일 상태는 `POLICY_PENDING` → `READY` → `LEASED` → `DONE`이다. 확정되지 않은 보존기간은 `0`으로 바꾸지 않는다. 실패는 `RETRY`로 남기고 지정된 횟수 후 `FAILED`가 된다. 사용 중인 외부 파일은 `REFERENCE_PENDING`으로 보존한다. 계정 tombstone은 물리삭제 작업이 아닌 `RECORDED`다. 원장 자동 삭제·보존기간 정책은 아직 없다.

`V56__durable_deletion_journal.sql`은 원장과 중복 기록을 직렬화하는 singleton lock을 추가한다. 원장에는 사용자/자료 FK를 두지 않아 원본 행 삭제 후에도 의도가 유지된다. V53 → V54 → V55 → V56 순서가 필요하다. create-drop 테스트만 singleton fixture를 별도로 만든다. 운영 코드가 누락된 migration을 자동 보완하지 않는다.

User → 소유 자료의 ID 순서 → 원장 mutex → intent 순서로 기록한다. Worker는 자료 → intent 순서를 사용한다. lease 조회 전에 읽는 값은 변경 불가능한 projection이며, 실제 변경은 잠금 이후 현재 행을 읽는다. 외부 AI와 실제 파일 삭제 동안 DB 트랜잭션을 유지하지 않는다. 렌더 파일 저장만 자료 잠금 안에서 짧은 로컬 쓰기로 수행한다.

## 재시도와 비활성화

`edupilot.deletion.enabled` / `EDUPILOT_DELETION_ENABLED` 기본값은 `false`다. 활성화에는 명시적인 `policy-version`이 필요하다. `original-retention-days`, `render-retention-days`, `external-retention-days`, `avatar-retention-days` 중 없는 값은 해당 파일 종류를 계속 보존한다. 승인되지 않은 종류는 승인된 종류의 큐를 막지 않는다. 정책 변경이나 복원 import로 이미 기록한 보존 종료시각을 단축하지 않는다.

별도 executor(core 1 / max 2 / queue 25)가 물리삭제를 수행한다. 포화로 제출이 거절된 작업은 claim하지 않아 다음 poll에서 다시 조회된다. 프로세스 중단 후 만료 lease를 새 토큰으로 회수하고 이전 token/generation의 완료 보고는 거절한다. `FAILED`의 `retryFailed`는 조사 후 호출하는 내부 운영 경계이며 공개 HTTP API가 아니다. 오류는 종류별 코드와 task 번호만 기록한다. provider 예외 본문·파일 ID를 로그에 남기지 않는다.

원본/아바타의 누락과 이미지 폴더 누락은 멱등 성공이다. 렌더 정리는 정확한 UUID 폴더의 숫자 `.jpg` 파일만 삭제하며, 예상 밖 파일·중첩 폴더·symlink는 실패로 남겨 보존한다. 실제 AI endpoint는 provider 파일 누락(404)을 멱등 성공 204로 반환한다. Spring은 AI 경계의 404(라우팅/배포 오류 가능)를 완료로 처리하지 않고 실패/재시도로 추적한다. `MaterialXaiFileLifecycleService.deleteAfterCommit` 이름은 기존 caller 호환을 유지하지만 이제 호출 트랜잭션에 원장을 저장하고 실제 삭제를 직접 실행하지 않는다.

현재 AI provider(`ai-service/src/edupilot_ai/llm/files.py`)의 기존 삭제 로그에는 `fileId`가 남는다. 이 Spring 작업은 AI 소스를 수정하지 않았으며 AI 계층의 마스킹/관측성 조율은 #469 담당 경계에 남는다. Spring worker에서의 비노출 검증을 전체 계층의 로그 비노출 완료로 해석하지 않는다.

삭제 후 늦게 끝난 추출·backfill은 자료 상태와 원장 tombstone을 확인해 결과를 연결하지 않는다. 렌더 저장은 동일 자료 잠금과 tombstone 검사를 통과해야 한다. 탈퇴 후 자료 upload는 User 행 잠금으로 거절하고 아직 공개하지 않은 신규 임시 업로드만 롤백 정리한다. 소유 자료의 기존 논리삭제는 유지되므로 탈퇴 강사의 자료에 학생이 계속 접근할 수 있다고 보장하지 않는다.

## 복원 절차의 코드 경계와 운영 미완료

`DeletionJournal.exportPage(afterId, limit)`는 cursor와 변경 불가능한 snapshot을 반환하며 이미 확정한 `retainUntil`도 포함한다. 빈 원장이나 더 짧은 현재 정책으로 복원하더라도 import는 기존 행과 반출한 보존기한 중 더 긴 값을 유지한다. `retainUntil` 없는 이전 반출 형식으로는 이미 확정한 기한을 복구할 수 없으므로, 운영 반출 도구도 이 필드를 보존해야 한다. 삭제 이력을 그 삭제보다 오래된 백업과 함께만 보관하면 복원 시 이력을 잃는다. 별도의 접근 통제된 원장 보관·무결성 검증·반출 보존기간·실제 운영 도구 연결은 #408과 함께 결정/검증해야 한다. 이 PR은 외부 영구 보관 서비스나 자동 운영 복원 명령을 제공하지 않는다.

운영자는 트래픽과 worker를 중단하고 기존 프로세스/외부 호출의 종료를 확인한 다음, 신뢰할 수 있는 별도 원장 snapshot을 `DeletionRestoreService.restoreBatch(snapshots, restoreEpoch)`에 넘긴다. 이 서비스는 공개 API가 아니다. import를 먼저 commit하고 각 논리삭제를 별도 트랜잭션으로 재적용하므로 중간 실패도 같은 batch/epoch로 재개할 수 있다. 동일 복원 epoch는 이미 완료한 물리삭제를 다시 열지 않는다. 다른 복원에는 새 epoch가 필요하다. 신뢰할 수 없는 JSON/사용자 입력을 그대로 import하지 않는다.

계정은 사용자 ID, 가입 시각(마이크로초), 원래 이메일 해시가 일치해야 재탈퇴한다. 다른 계정은 `IDENTITY_MISMATCH`로 보존한다. 자료는 숫자 DB ID 대신 UUID storage key로 찾는다. 복원 재탈퇴는 기존 소유 강의실 종료·자료/세션 논리삭제·토큰 회수 hook을 재사용하며 완료 메일을 재발송하지 않는다. 백업에 이전 아바타가 남아 있으면 재탈퇴로 키를 지우기 전에 확보하고 같은 트랜잭션에서 추가 삭제 기록을 남긴다. 완료 파일 작업도 새 복원 epoch에서 다시 대기하게 해 복원된 파일을 승인된 보존정책에 따라 정리할 수 있다.

현재 합성 테스트는 원장 import·계정/자료 행의 복원 상태·재적용·중복 epoch·ID 불일치·파일 재삭제를 검증한다. 실제 운영 백업 복원, 모든 hook의 운영 데이터 검증, 영구 원장 보관, 유료 AI·메일 수신은 별도 접근/승인 이후 작업이다. 알려지지 않은 보존기간을 정하거나 물리삭제를 활성화하지 않는다.
