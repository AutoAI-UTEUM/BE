# NOTE-04 FE 인수용 합성 fixture 계획과 로컬 검증

브랜치 한 줄: `feature/479-note04-local-fixture` — NOTE04의 합성 OX 103개·manifest·정리 rollback을 실DEV 접속 없이 검증한다.

현재 상태는 **로컬 합성 검증 완료 / 실제 DEV 미실행 / 생성 승인 대기**다.
이 디렉터리는 기존 NOTE04/#479 준비계획을 구체화한다. 실제 계정·동의·자료·세션·퀴즈를 만들거나
DEV를 조회하지 않았고, 서버·SSH·실AI·메일·SMS·영구삭제를 실행하지 않았다.
10/4 FE 후속 요청에 따라 현행 FE224의 자동 전체 조회·세션 단위 결과·실패 세션 page0 재시작에 맞춰 인수 문구를 정정했다.
전용 ID/최소 메타와 실제 생성 방법·공유 DB 원장도 [실행계획](dev-execution-plan.md)에 구체화했다.
작업 재개 후 [실제 schema 기반 로컬 MySQL adapter](mysql-local-adapter.md)를 추가했다.
이 adapter도 본인이 시작한 임시 process만 사용하며 실환경 target을 수락하지 않는다.

## 기준과 재사용한 자료

- [PR481 절차 댓글](https://github.com/AutoAI-UTEUM/BE/pull/481#issuecomment-5969927454)의
  101건·동일 createdAt·ID DESC·페이지 메타데이터·타인 404·범위 400 검증을 재사용했다.
- [#479 최종 인계](https://github.com/AutoAI-UTEUM/BE/issues/479#issuecomment-5978220093)의
  run label, manifest ID, 2/3/3/103 구성, 정리/인수 담당 제안, 별도 승인과 실응답/모의오류 구분을 유지했다.
  댓글이 언급한 `evidence/note04-dev-fixture-plan.json` 원본 파일은 이 작업에서 읽거나 수정하지 않았다.
- [원래 AutoAI 개발 요청](https://discord.com/channels/1542688939742339153/1555440530643746996/1556210221318406196)은
  위임 내용에 따르면 준비계획 요청이다. Discord 본문은 이 세션에서 다시 조회하지 않았다.
- [통합 PR486](https://github.com/AutoAI-UTEUM/BE/pull/486)은 develop에 병합됐다
  (`6566ac77c93a443d527b14c9bf048ce40b6577ac`). DEV 배포 상태는 #479 인계/부모 전달 기준이며
  이 작업에서 서버·DB·배포 endpoint를 확인하지 않았다.
- 고정 base: `origin/develop`의 `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`.
  독립 clone `BE-note04-fixture`, 브랜치 `feature/479-note04-local-fixture`.
  저장소 `AGENTS.md`, 협업/PR/테스트 지침과 실제 controller/service/DTO/migration을 읽었다.
  clone에 별도 `.agents/skills` 또는 하위 `AGENTS.md`는 없었다.
- 통합 검토 대상은 [draft PR495](https://github.com/AutoAI-UTEUM/BE/pull/495)의
  재개 시 읽은 `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e` snapshot이다. 이는 배포 SHA가 아니다.
  V1–V53 baseline과 이 후보의 V1–V59를 각각 local MySQL에서 검증한다. 둘 모두 immutable Git source를 읽는다.
  배포 설정·FE/메일 준비·migration·복구 조건은 [PR501의 고정 문서](https://github.com/AutoAI-UTEUM/BE/blob/fd12b01ecfef34b45629e2a8efe4068a4b5861c1/docs/launch-deployment-preflight.md)를 읽고 참조한다.
  이 디렉터리에서 배포/rollback 런북을 중복 작성하거나 설정을 바꾸지 않는다.

실제 목록 계약은 [API 명세](../../api-spec.md)와
[SessionQuizController](../../../main-service/src/main/java/io/edupilot/quiz/SessionQuizController.java),
[QuizService](../../../main-service/src/main/java/io/edupilot/quiz/QuizService.java),
[QuizSummaryResponse](../../../main-service/src/main/java/io/edupilot/quiz/dto/QuizSummaryResponse.java)를 기준으로 한다.
공통 코드·기존 테스트·빌드 설정·migration은 변경하지 않았다. FE 저장소는 변경하지 않았다.

## 정확한 fixture 범위

제안 run label: `NOTE04-DEV-20261004-R1`.
제안 manifest ID: `note04-dev-20261004-r1-plan`.

| 학습자 | 자료 alias | 세션 alias | OX 퀴즈 수 | 용도 |
| --- | --- | --- | ---: | --- |
| A | A1 | A1 | 101 | 100개 초과와 같은 생성시각의 정렬 tie-break |
| A | A2 | A2 | 1 | 동일 계정의 다른 자료/세션 sentinel |
| B | B1 | B1 | 1 | 다른 계정의 sentinel과 소유권 |

총 합성 학습자 2명, 자료 3개, 세션 3개, **퀴즈 레코드 103개**다.
각 퀴즈에는 결정적인 합성 OX 문항 1개를 둔다. A1의 101개는 정확히 같은 `created_at`이고
모두 미제출이다. sentinel도 미제출로 동결한다. 공개 목록의 PDF `page`는 7로 설정하여 목록 페이지 0/1과 구별한다.
로컬 고정 시각은 `2026-10-04T00:00:00.000Z`; 실제 생성 시각 확정은 승인된 실행 manifest에서 한다.
FE는 더 보기 없이 자동 조회하며, A 계정의 완료 목록은 A1 101개+A2 1개=102개, B는 B1 1개다.
실패한 A1의 부분 100개를 표시/보존하는 것을 기대하지 않는다. 다른 성공 세션은 보존하고 실패 세션만 page0부터 다시 조회한다.

이 로컬 DB에는 범위 보존 검사용 합성 observer 행도 있다. observer는 위 2/3/3/103에 포함하지 않으며
실제 사용자/자료를 흉내 낸 기존 DEV 행이 아니다. 대상 외 행의 snapshot hash를 비교한다.

## 실행

SQLite/FE mock은 Node 24 이상(`node:sqlite` 내장)을 사용한다. 이 경로에는 npm 설치·서버·포트·MySQL·Docker·Gradle이 필요 없다.
저장소 루트에서:

```text
node scripts/qa/note04-fixture/fixture.mjs
node scripts/qa/note04-fixture/fixture.mjs --mode local-rehearsal
node scripts/qa/note04-fixture/fixture.mjs --include-mock-responses
node --test scripts/qa/note04-fixture/fixture.test.mjs
```

FE source와 소비자 mock을 함께 확인하려면 본인의 읽기 전용 FE checkout을 지정한다.
고정 FE source head는 `1b6987d8472a064a080a00bd43232993e9bd41eb`; 테스트는 clone/read만 하고 FE 파일을 쓰지 않는다.

```powershell
$env:NOTE04_FE_SOURCE_ROOT = 'C:\path\to\FE-readonly'
node --disable-warning=ExperimentalWarning --test scripts/qa/note04-fixture/fixture.test.mjs scripts/qa/note04-fixture/fe-consumer.test.mjs
```

fixture 31개 + 실제 FE repository/추출 effect/helper mock 9개를 검증한다. React/브라우저·실HTTP·인증 테스트가 아니다.
FE source 변경/미지정 시 소비자 검사를 실패시키며 자동 fetch/설치하지 않는다.

- 기본 `dry-run`: 메모리 DB의 단일 transaction에서 생성/검사/응답 투영 후 **ROLLBACK**한다.
- `local-rehearsal`: 합성 메모리 DB에서만 seed를 commit하고, 정리 DELETE를 모의 실행한 뒤 rollback한다.
  종료 시 연결을 닫아 메모리 DB를 없앤다. 외부 환경에 대한 apply 기능은 없다.
- `--include-mock-responses`: 전체 success/error envelope를 stdout으로 제공한다. 반드시 `LOCAL_MOCK_ONLY`로 취급한다.
  답안·JWT·비밀번호·동의 원문은 출력하지 않는다. manifest ID/행 hash/최소 메타만 포함한다.
- 파일 경로·DB URL·DSN·token·`--apply`·`--mode dev` 인자는 거절한다. `.env`나 애플리케이션 DB 변수는 읽지 않는다.
  factory는 target 인자를 받지 않고 `new DatabaseSync(':memory:')`만 사용한다.
  다른 connection, ATTACH, foreign key 비활성화도 거절한다.

SQLite experimental 경고는 Node 런타임 안내다. 이 작업의 실제 실행기는 Node `v24.12.0`이었다.
생성한 stdout JSON은 로컬 모의 증거이며 source에 commit할 DEV manifest가 아니다.
로컬 숫자 ID를 DEV API/SQL/정리 요청에 복사하면 안 된다.

실제 MySQL schema를 검증하는 별도 로컬 경로는 Windows의 설치된 MySQL 8.0 계열(8.0.22 이상)을 사용한다.
전용 datadir/shared-memory process·실제 V1–V53·최소 권한 seed/cleanup 역할과 원장을 준비한다.
TCP·기존 MySQL 서비스·Docker·Gradle은 사용하지 않는다. 자세한 입력/권한/검증 한계는 [adapter 문서](mysql-local-adapter.md)에 있다.

```powershell
node scripts/qa/note04-fixture/mysql-rehearsal.mjs
node scripts/qa/note04-fixture/mysql-rehearsal.mjs --mode=local-rehearsal --local-approval=LOCAL_SYNTHETIC_COMMIT --cleanup-local-approval=LOCAL_SYNTHETIC_DELETE
node scripts/qa/note04-fixture/mysql-rehearsal.mjs --schema=pr495-v59
node --test scripts/qa/note04-fixture/mysql-adapter.test.mjs
```

`pr495-v59`는 위 후보 SHA의 Git object를 독립 clone에 읽기용 fetch한 뒤 사용한다. 실행기는 fetch/download하지 않는다.
input과 manifest의 schema source SHA가 일치해야 하며, 다른 schema/target으로 바꾸면 거절한다.

## 다음 단계

1. [승인·manifest·invariants·최소권한](approvals-and-manifest.md)을 검토한다.
2. [전용 ID·메타 조회·103개 생성·원자적 DB 원장 계획](dev-execution-plan.md)과 [로컬 구현](mysql-local-adapter.md)을 검토한다. 실환경 IDs/권한/원장 설치/운영 adapter는 여전히 미확정·미구현이다.
3. [비밀값 없는 FE handoff와 현행 인수 시나리오](fe-handoff.md)를 별도 auth 계약 문서 작업과 연결한다.
4. [로컬 검증 기록](local-validation.md)을 실제 DEV 실응답 증거와 별도로 보관한다.

실DEV 재사용 메타조회·쓰기·계정 생성·인증 전달·동의·영구삭제 승인은 아직 없다.
런타임/공통 코드/migration/최종 통합은 기존 구현 세션 담당이다. SQLite 실행기는 그대로 유지하며 별도 로컬 MySQL adapter만 추가했다.
실환경 실행기는 승인된 범위와 당시 배포 schema를 기준으로 별도 검토한다. 로컬 target 차단을 제거해 DEV에 사용하지 않는다.

Related to [#479](https://github.com/AutoAI-UTEUM/BE/issues/479), NOTE-04, BE PR481/486, FE PR224.
전체 출시 인수 완료를 의미하지 않는다.
