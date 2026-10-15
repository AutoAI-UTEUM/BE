# 출시 후속 로컬 검증 · 2026-10-15

요구사항 원문은 [메인](https://app.notion.com/p/3ee4bbcf8535801fa206e76bac01a1ed)과
[출시 검증](https://app.notion.com/p/1044bbcf85358317a6f801faeed808d9)의 2026-10-15 갱신이다.
125개 요구사항·출시 필수 92/출시 후 21/결정 대기 12 분류를 바꾸지 않는다.
아래 부분 검증은 전체 연동 인수·출시 승인과 구분한다.

## 기준과 보존

- GitHub develop을 읽어 `8163a8269a2a68c4595b0643caea62e91c1f5e1c`를 확인했다.
  PR548의 tree는 `88a5ecb6ff8812c67b69e492426f4745bff872d9`다.
- 원래 `2026-10-03/task-2`의 checkout·브랜치·사용자 파일은 변경하지 않았다.
  쓰기 가능한 별도 사본 `2026-10-15/task/BE-validation`의
  `feature/479-launch-followup-20261015`에서 작업했다. 새 commit/push/PR/병합/배포는 없다.
- 기존 `888629c7c76205810bfb83978d7880230d2c69b1`의 메일 스케줄러 재시작 테스트 2개 파일과
  CI receipt 업로드 1줄을 그대로 재사용했다. 새 scheduler 구현은 없다.
- `evidence/pr548-dev-deploy-20261013`의 기존 manifest 13개 SHA-256이 모두 일치했다.
  이는 과거 파일 무결성 확인이며 현재 DEV DB·컨테이너 조회 결과가 아니다.
- V64를 포함한 migration과 운영 설정·보호자/메일 활성화 설정은 변경하지 않았다.
- FE는 별도 사본 `FE-followup`의 `test/479-exam-score-reason-ui-20261015`에서 작업했다.
  기준 develop은 `0ae5650e5796ce3b217c389e253b676265b89e48`다. 의존성·lockfile은 그대로다.

## 재현하고 수정한 경계

### MAIL-01·02: 비어 있는 공급자 수락 ID

수정 전 실제 `SesEmailSender` + mock SES 응답 + 실제 H2 outbox 경로에서 null·빈 값·
ASCII/Unicode 공백 ID가 `SENT`로 저장됐다. 성공 결과의 `EmailDeliveryResult`가 빈 ID를
거절하게 하여 기존 worker의 불확실 결과 경로로 처리한다.

회귀는 sender 5건과 영속 outbox 4건이다. `UNKNOWN`, 실패 감사 이력, 수락 ID/발송 시각 없음,
payload 제거, 공급자 호출 1회, 회수·직접 kick·새 worker 이후에도 재발송 없음을 검사한다.
SDK client는 mock이고 실제 메일은 발송하지 않았다.

### AUTH-04·MAIL-02: 재설정 outbox INSERT 롤백

수정 전 실제 service/outbox transaction proxy와 H2 CHECK 제약 실패에서
`UnexpectedRollbackException`이 service 반환 뒤 발생하여 `/password-reset/request`가
500을 반환했다. 기존 계약은 가입 여부·발송 성공과 무관한 동일 202다.

요청 controller에서 해당 commit 롤백만 처리하여 같은 202/data를 반환한다. 확정 endpoint와
일반 예외 처리는 그대로다. 원자성은 유지하며 새 토큰·기존 토큰 무효화·payload가 함께
롤백된다. 새 회귀 1건은 기존 비밀번호·미사용 토큰·다른 요청 ciphertext 보존과 후속 정상
요청 commit, 공급자/AI 호출 0회도 검사한다. 로컬 합성 데이터에만 실패를 주입했다.

### EXAM-03·SEC-03: 강사 선택 사유 입력과 재조회

강사 답안 상세에 선택 사유 입력·현재 저장 사유 표시를 추가했다. API의 원문 길이 계약과
같은 500 UTF-16 상한을 적용하며 전송 전에 공백을 잘라 길이를 우회하지 않는다.
저장 응답의 정규화된 사유를 표시하고, 같은 점수에서 사유 교체·삭제도 가능하다.
저장 실패 시 입력을 보존하고 다른 문항의 미저장 초안을 덮어쓰지 않는다. 다른 학생이나
계정·경로로 이동하면 이전 화면/늦은 응답·사유 초안을 기존 scope 격리로 차단한다.

repository의 기존 AbortSignal 호출 순서는 유지했다. 학생 화면·권한·전체 변경 이력과
사유 필수화/학생 공개 정책은 변경하지 않았다. 로컬 mock 검증과 실제 계정·BE 연동
인수는 별도이며, 실제 성적 데이터를 생성하거나 변경하지 않았다.

390px Chrome 화면 확인 중 기존 공통 저장 알림의 왼쪽 좌표가 -10px인 잘림을 재현했다.
전체 viewport 너비에 오른쪽 margin을 더하던 폭을 좌우 여백을 포함한 폭으로 보정했다.
수정 전 geometry 실패와 수정 후 데스크톱/390px의 화면 경계 통과·스크린샷을 보존했다.

### QA-01·AI-01~06: Windows 회귀 실행

UTF-8 golden/report JSON·문서 읽기에 encoding을 명시했다. 플랫폼 공통의 파일 덮어쓰기
거절과 POSIX 권한 비트·symlink 조건을 나누어 기존 Linux 검사를 보존했다.
의존성/lockfile/AI 모델·실행 정책 변경은 없다. PDF 하위 worker는 작업 폴더의 pip 없는
가상환경과 기존 패키지를 사용했으며 저장소의 현재 소스를 불러온다.

## 실행 증거

원본 receipt·로그·XML·hash는 작업 폴더의 `evidence/launch-followup-20261015`에 보존한다.
각 receipt는 실행 UTC 시각·명령·cwd·종료 코드·기준 SHA를 기록한다. 최종 Main 실행은
컴파일 입력 파일 manifest의 SHA-256도 기록한다. 서로 다른 실행 수를 합산하지 않는다.

| 실행 | 결과 | 근거 |
| --- | --- | --- |
| 기준선 Main 전체 build: PR548 + 기존 scheduler 테스트 | 2,134 통과 / 30 skip / 실패 0 | `main-baseline-summary.json`, `main-baseline-results.zip` |
| 새 경계 수정 전 | 10건 실패로 두 경계 재현 | `main-repro-before.log`, `main-repro-before/test-results/` |
| 같은 새 경계 수정 후 | 10/10 통과 | `main-repro-after-summary.json`, `main-repro-after-results.zip` |
| 최종 후보 Main 전체 build | 2,144 통과 / 30 skip / 실패 0. 컴파일 입력 1,199개 파일이 실행 동안 동일 | `main-build.json`, `main-build-sources.json`, `main-final-summary.json`, `main-final-results.zip` |
| 실제 MySQL 8.0.43 메일 영속·sender | 31 통과 / skip·실패 0. 별도 합성 DB, 입력 1,199개 파일 동일 | `mysql-mail-acceptance.json`, `mysql-mail-results.zip` |
| 추가 MySQL 10개 suite | 선택 조건 정의 16개를 확장한 17건 통과 / skip·실패 0. 새 합성 schema 13개 식별 확인 | `mysql-remaining-acceptance.json`, `mysql-remaining-results.zip` |
| FE 선택 사유 집중 회귀 | 82/82 통과. repository·UI·학생 답안 및 강사 격리 | `fe-focused-final-command.json`, `fe-focused-final.log` |
| 최종 FE 전체·정적 검사 | 108개 파일·1,170/1,170 통과. 최종 9개 변경 파일 hash 동일. typecheck/lint 통과 | `fe-full-ci-final-command.json`, `fe-full-ci-final.log`, `fe-typecheck-final-command.json`, `fe-lint-final-command.json` |
| 최종 FE 브라우저 mock 인수 | Chrome 1440px·390px의 8/8 통과. 저장·재조회·삭제·실패·500 UTF-16·격리·알림 경계 | `fe-browser-command.json`, `fe-browser-results.json`, `fe-browser/` |
| AI not-live 회귀 | 1,064 통과 / 5 플랫폼 skip / 실패 0 | `ai-offline.json`, `ai-offline.xml` |
| AI Ruff format/lint·mypy | 모두 통과 | `ai-format.json`, `ai-lint.json`, `ai-types.json` |
| 오프라인 manifest/Compose/메일 거절 가드 | 189/189 통과 | `node-qa.json`, `node-qa.log` |
| 최종 JAR 발송 금지 경로 | 6/6 통과, Spring 시작·identity 조회·발송 없음 | `boot-jar-plan.json`, `boot-jar-plan.log` |
| 현재 DEV 익명 GET 4회·공개 계약 10개 | 10/10 통과, 2026-10-15 09:49 UTC | `public-current-result.json` |
| 과거 PR548 배포 증거 무결성 | 13/13 SHA-256 일치 | `previous-evidence-integrity.json` |

AI skip 5개는 Windows의 POSIX mode bits와 symlink 생성 불가, Linux 전용 RLIMIT_AS·CPU
budget·RSS 실측이다. Linux 운영 환경의 현재 자원 제한 인수로 계산하지 않는다.
Main skip 30개는 실제 AI 1개와 선택 MySQL 조건의 29개다. 구체적인 suite/case는
`main-skipped.json`에 남겼다. 이후 별도 MySQL 메일 검증은 sender 10건과 outbox 21건이며,
이 중 MySQL 전용 migration 1건과 누락된 SES receipt 4종을 실제 MySQL에서 실행했다.
Main 실행의 skip 수를 소급해서 바꾸거나 서로 다른 실행 수를 합산하지 않는다.
해당 임시 MySQL 서버는 소유 PID·datadir 확인 후 정상 종료했고 33316 포트가 닫혔다.
Chrome도 local mock build/preview만 사용했으며 종료 후 4187 포트가 닫혔다.

추가 MySQL 실행에서는 인증·삭제·생년월일/접근 cohort migration, 강의실 탈퇴/편집의 잠금
순서, 보호자 web의 재발급/동시 첫 요청·회수, 노트 경합/유일키를 실제 MySQL에서 검증했다.
기존 30개 미실행 목록의 MySQL 정의 17개는 두 별도 실행에서 확인했다. 나머지 12개 정의는
4개 suite가 이전 `task-2/mysql-isolated`의 datadir를 정확히 요구하므로, 해당 경로와 가드를
변경하지 않고 미실행으로 보존했다. 실 AI 1개도 미실행이다. parameter 확장 전 정의 수와
실행 case 수를 구분하며 `mysql-remaining-blocked-details.json`에 파일/줄/hash가 있다.
일부 미실행 fixture의 version 63 기대와 현재 V64 존재는 정적 관찰이며 런타임 실패로
확정하지 않았다. 추가 서버 역시 정상 종료·포트 해제를 확인했다.

최초 샌드박스에서는 로컬 Gradle 연결·Git Bash/하위 Python 실행이 막혔다. 그 결과는 별도
`*-sandbox-*`로 남겼다. base Python만 사용한 첫 AI 실행의 하위 worker import 실패도
`ai-offline-base-interpreter.*`로 보존하고, 가상환경 구성 후 실행과 구분한다.
이 실행환경 실패를 제품 결함으로 세지 않았다.

FE 첫 전체 실행에는 CI의 `VITE_API_BASE_URL=/api`와 capability 설정이 누락되어 환경
실패로 분리했다. 첫 브라우저 실행의 중복 textarea/본문 선택자 실패도 테스트 하네스
수정으로 분리했다. 원본 로그와 시작/종료 소스 hash를 보존했으며 timeout 완화는 없다.
명시적인 CI 환경에서 mock build 후 브라우저를 실행했다. 기존 Vite chunk 크기 안내는
남아 있으며 이번 기능 검증이 번들 성능·실기기 인수를 대체하지 않는다.

## 노션 후속 7개 묶음의 완료 조건과 남은 경계

| 요구사항 | 이번 근거·검증한 범위 | 남은 완료 조건·차단 |
| --- | --- | --- |
| EXAM-03 / SEC-03 | 현재 OpenAPI 선택 사유·500 UTF-16 상한·강사 필드·학생 스키마 제외, Main의 점수/사유·합계·행 잠금/학생 비노출 회귀. FE 강사 선택 사유 입력·재조회·초안/학생 간 격리 구현과 mock 회귀 | 실제 FE↔BE·학생 인수는 승인된 계정/시험 데이터 필요. 전체 이력 접근권·보존 기간·사유 필수화·학생 공개는 정책 결정 필요 |
| QUIZ-03 / EXAM-02 / LEARN-03·04·08 | 기존 draft 충돌·빈/Unicode 답안·저장 답안 재채점·lease fencing·JVM 교체·SSE 회귀를 전체 Main에서 실행. AI 완료/실패/취소 및 PDF worker 회귀 | 실제 FE 지원 버전/플래그·통신 단절·새로고침 인수, 실제 제출/AI 호출은 데이터·비용 범위 승인 필요 |
| CLASS-01 / MAT-01 / QUIZ-02 / SEC-01 | 기존 역할·강의실/리소스 격리와 권한 회수 후 스트림 회귀를 Main에서 실행. 실제 MySQL의 강의실 탈퇴/편집 잠금 순서·노트 경합도 통과. 익명 users/me 401 재확인 | 실제 역할별 캐시·다운로드·내보내기 E2E와 권한 변경 시나리오 필요. 실제 계정/권한 변경은 별도 승인 |
| MAIL-01·02 / AUTH-01·04 | 기존 token 일회성·GET 비소비·만료/재발급·경합, 새 불확실 영수증·재설정 롤백, 기존 scheduler JVM 교체를 검증 | 템플릿은 인증/재설정/탈퇴 완료 3종. 계획 4건은 인증 재발급을 더한 횟수. 실제 inbox 도착은 미검증. logging/PAUSED·0/4·guardian OFF는 10월 13일 마지막 기록이며 이번 현재 설정 확인 아님 |
| MAT-03·04 / OPS-01~05 | mock/fake AI 하의 실제 PDF worker·timeout/취소/회수, manifest·Compose·복구 계획 가드. 공개 health/readiness 정상 | 원본 PDF 500의 실제 자료 재현·DB와 원본 파일 restore·경보 수신·rollback 인수 미검증. 실제 복원·재시작·배포는 대상/보존/승인 필요 |
| AI-01~06 / UI-04·05·06 / QA-01~06 | AI not-live 1,064건과 Main의 비용 결측/할당량·실패 복구 회귀, static checks | 대표 실제 자료의 유료 모델 품질·주입/비용·성능 인수, Linux 자원 실측, 실기기·IME·스크린리더·접근성은 미검증 |
| AUTH-01·07 / DATA-01·03·05·06·07 / BIZ-01 | 기존 가입·동의·guardian gate·삭제/보존 worker 회귀를 Main에서 실행. 분리 MySQL의 인증·삭제·생년월일/cohort migration·보호자 web 경합도 통과 | 옛 datadir에 고정된 보호자 consent fence·admin lock·full migration·turn consent 4개 suite는 미실행. 공개 가입 활성화·미성년자/보호자·보존/삭제·외부 AI 처리·상용 조건은 확정 정책과 실제 환경 인수 필요. 임의 정책 선택/활성화 없음 |

현재 공개 검사는 health/readiness·OpenAPI·익명 접근 거절만 확인한다. 실행 중인 컨테이너 SHA,
DB migration/답안 행·Spring resolved property·메일/guardian 설정·실제 학생 응답을 확인한 것은 아니다.
과거 10월 13일 DEV postdeploy 증거와 이번 10월 15일 공개 GET 결과를 혼동하지 않는다.

외부 메시지·실메일·유료 AI·실사용 데이터 변경·권한 변경·새 병합/배포·운영 복원은 하지 않았다.
포트폴리오 증빙은 범위에서 제외했다. 이번 로컬 수정의 코드 검토·게시/CI·승격은 별도이며
원문의 전체 연동 인수 완료 수를 올리지 않는다.

검토용 BE `candidate.patch`의 기준은 위 PR548 develop SHA이며 기존 scheduler 테스트까지
포함한다. 같은 PR548 기준에 해당 테스트가 이미 있으면 `new-fixes.patch`를 사용한다.
과거 `888629c7`만 있는 구형 브랜치에 새 패치를 바로 적용하는 용도는 아니다.
FE `fe-candidate.patch`는 FE 기준 SHA에 별도로 적용한다. 두 패치의 깨끗한 기준 사본에서
`git apply --check --whitespace=error-all` 결과와 hash는 `patch-apply-check.json`,
`candidate-source-manifest.json`, `completion.json`에 보존한다.
