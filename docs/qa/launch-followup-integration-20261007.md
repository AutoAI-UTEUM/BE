# 출시 후속 회귀·FE 계약 통합 — 2026-10-07

저장·메일·운영 인수의 독립 근거와 TEAM_REVIEW FE 계약 보완을 PR533 exact `28e265397dc52667fd5e7f98f1e782ba2ddafef7` 위에 통합한다. 기존 runtime을 중복 구현하지 않는다. PR532/533의 검토 head를 변경하지 않으며 develop/main merge·배포는 실행하지 않는다.

`feature/479-launch-followup-integration`: draft/persistence/mail/운영 인수 회귀와 보호자 FE 서버 진입·구조화 DTO를 정확 source commit으로 통합한다. 관련 이슈 #479·#526, 메일 범위 #473 및 운영 #519·#515.

| 독립 브랜치·PR | 통합한 정확 commit | 한 줄 결과 |
| --- | --- | --- |
| feature/479-draft-recovery-regressions · [PR534](https://github.com/AutoAI-UTEUM/BE/pull/534) | `1a842aef21450b3041e024893762dce6de9b5574` | 최초 draft 경합·실제 rollback·409 최신 답안 재조회 회귀 |
| feature/persistence-acceptance-20261007 · [PR535](https://github.com/AutoAI-UTEUM/BE/pull/535) | `30f850a8ec8f56c4202c1ae10bdd6549fd8c97d8` | draft/regrade/user-notes commit·flush 실패·동시 중복·독립 인증 복원 합성 인수 |
| feature/mail-flow-acceptance-20261007 · [PR536](https://github.com/AutoAI-UTEUM/BE/pull/536) | `4b2ae1438ace1a151eed82cb8c316cae84a90e46` | reset token/outbox 동일 expiresAt 수정과 가입/reset/탈퇴 durable mock-mail 합성 인수 |
| feature/operations-acceptance-20261007 · [PR537](https://github.com/AutoAI-UTEUM/BE/pull/537) | `dec9247e716eee10fa89b4b7bd4030cce67b2a26` | 기존 운영6묶음 metadata·SELECT 집계 계획·미실행/unknown 보존과 오프라인 점검 |
| feature/526-guardian-fe-contract · [PR538](https://github.com/AutoAI-UTEUM/BE/pull/538) | `cca982c5a1663ee7d474446390618c371b151806` | 서버 최초 진입·replyChannel·담당자 generationStartedAt/declaredScopes 계약 보완 |

각 exact source commit을 merge하여 독립 검토 이력을 보존했다. 파일 충돌 없이 통합했고 변경 runtime은 reset 서비스, guardian DTO/service/self controller와 신규 entry의 본인 조회 예외뿐이다. 기존 draft/regrade/user-notes/report API·dueAt 표시/명시 close를 다시 구현하거나 바꾸지 않는다. 최종 API와 최초 진입/nullable/DECLARED·서비스/외부AI 범위 구분은 [FE 계약](../guardian-team-review-fe-contract.md)을 따른다.

집중 실행 증거는 [draft52/52](exam-draft-recovery.md), [persistence9/9](persistence-acceptance-20261007.md), [메일 RED→수정/합성 인수](mail-flow-acceptance-20261007.md), [보호자33/33(신규7)](guardian-fe-contract-20261007.md)에 있다. 서로 겹치는 기존 검사 건수를 더하여 전체 실행 수로 보고하지 않는다.

통합 checkout에서 운영70 + 기존 readiness82 = **152/152 PASS**, mail-trial guard **27/27 PASS**, bash 문법 PASS, 실제 bootJar plan **6/6 PASS**를 확인했다. C의 pinned109개 기존 source/migration 참조도 일치했다. Boot plan은 sends0·SpringStarted=false·executionAuthorized=false이며 실제 발송·계정 권한·서비스 설정을 건드리지 않는다.

전체 Spring 실행은 JDK21/Gradle9.5.1 offline `-Dit.ai=false --no-daemon --max-workers=1 --console=plain build`로 별도 검증한다. 최종 통합 commit과 fresh XML의 total/pass/fail/error/conditional-skip, Main/AI CI job·필수 단계는 통합 PR의 최종 검증 기록에서 확인한다. MySQL 조건부 검사의 skip을 성공으로 집계하거나 과거 PR532의 합성 MySQL42 실행을 새 head에서 재실행한 것으로 표시하지 않는다. AI 필수 formatting/lint/type/pytest는 게시한 정확한 head CI에서 실제 수행한다.

PR535 AI attempt1은 테스트 단계에서 취소됐고 원인 단정 없이 해당 job만 재실행해 같은 SHA attempt2 SUCCESS를 확인했다. PR537 AI attempt1도 같은 방식으로 취소 후 해당 job만 재실행해 SUCCESS를 확인했다. workflow15분 제한과 시각이 비슷하나 취소 원인 로그가 충분하지 않으므로 코드 실패나 timeout으로 단정하지 않는다. 성공한 Main job을 다시 실행하지 않았다. C의 Main은 tools/docs diff라 Java build가 skip된 success이며 전체 Java 인수 증거로 쓰지 않는다.

DEV main/ai 로그 각14일과 지정 담당자 ACTIVE ADMIN은 완료된 사용자 실행 이력이다. 재조회·재승격을 요청하지 않는다. SDK identity1회/TEST1건 예산도 소진됐고 가입/reset/탈퇴 실제3종 서비스 메일 인수의 대체 증거가 아니다.

운영 정책의 링크1h·신청4d·승인/증거90d는 미채택 제안이며 이번 통합으로 설정하지 않는다. 실제 관계 확인·최종 고지·승인 종료/목적별 파기와 이미 보류한 삭제 예외, 구체적인 추가 메일 예산·queue 격리/UNKNOWN 처리·BE/FE 전환/복구 책임은 후속 결정이다. TEAM 기본 OFF, 미정 정책 차단, 물리 삭제OFF를 유지한다. 현재 서버 artifact/마이그레이션/선택 flags·FE served hash·실브라우저/역할/학교NAT/worker 장애 인수·메일 수신·원본 파일 백업/새 호스트 복구·경보 수신과 회복·비용 대사는 실제 환경 증거가 따로 필요하다. [운영6묶음 인계](operations-acceptance-20261007.md)는 준비 계획이며 실제 실행 완료가 아니다.
