# 시험 답안 draft 실패 복구 회귀 (#479)

2026-10-07 검증 기준은 develop `b5f659bf223daff2a4cbda7b36c2fbae543494e7`다. 기존 draft API와 학생 재채점 API를 사용하며 새 기능이나 운영 설정을 추가하지 않는다. Discord 요청 `1555840753727447183`의 저장·충돌·실패 복구 근거를 보완한다.

브랜치 한 줄: `feature/479-draft-recovery-regressions` — 첫 답안 저장 경합·DB 롤백 복구·HTTP409 최신 답안과 새 토큰 재조회 일치를 검증한다.

| 추가한 회귀 | 실제 검사 |
| --- | --- |
| 두 클라이언트의 첫 저장 경합 | 동시에 version 0과 서로 다른 답안을 저장한다. 성공 1건·충돌 1건이고, 충돌의 latestDraft와 별도 조회한 영속 답안·version이 같다. |
| 변경 후 트랜잭션 롤백 | 실제 H2 트랜잭션 안에서 version을 증가시킨 뒤 롤백한다. 새 조회에는 직전 commit의 답안·version·서버 시각이 남고, 같은 기대 version으로 재시도하면 한 번 증가한다. |
| HTTP409 및 새 토큰 복원 | stale PUT의 DRAFT_VERSION_CONFLICT 응답이 최신 답안·version·savedAt을 반환한다. 같은 계정의 새 토큰으로 GET한 값이 동일하고 stale 본문이 덮어쓰지 않는다. |

기존 시험을 함께 실행해 저장답안 고정 재채점·동시 복구/worker·동시 제출·결과 공개 제한·재응시·dueAt 표시와 명시적 close 계약을 보존했다. 재채점이나 clientId 계약을 재개발하지 않았다.

```powershell
gradle -Dit.ai=false --offline --no-daemon test `
  --tests io.edupilot.exam.ExamAttemptDraftJpaTest `
  --tests io.edupilot.exam.ExamFailureSecurityJpaTest `
  --tests io.edupilot.exam.StudentExamJpaTest
```

위 세 suite **52/52 통과, 실패0·오류0·미실행0**. 새 시험은 3건이며 DB는 격리된 H2 메모리, AI는 stub이다. 최초 실행은 새 HTTP 시험의 시각 정밀도 fixture 1건이 실패했다. 입력 시각을 실제 저장 정밀도인 마이크로초로 고정한 후 위 전체가 통과했다. 런타임 시각 처리 코드를 변경하지 않았다.

이 증거는 브라우저 저장 장치·통신 단절·물리적으로 다른 기기·실DEV MySQL·프로세스 강제 종료 시험이 아니다. 기존의 다른 토큰 조회와 이번 새 토큰 조회도 실제 다른 기기 인수를 대신하지 않는다. 공개된 배포 workflow 성공과 runtime 인수는 따로 확인한다.

Related to [#479](https://github.com/AutoAI-UTEUM/BE/issues/479).
