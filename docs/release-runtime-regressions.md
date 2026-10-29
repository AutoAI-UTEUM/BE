# 출시 권한·동시성·worker 회귀검증 (#479)

브랜치 한 줄: `feature/479-runtime-regressions` — 현재 DB 권한과 자료 접근을 재검사하고 동시 제출·제한 큐 포화·새 worker 복구를 합성 MySQL에서 검증한다.

2026-10-04에 원본 125항목 중 출시 필수 92항목을 최신 코드 및 기존 테스트와 대조했다. 전체 보고서를 새 테스트만으로 모두 완료했다고 판정하지 않는다. 기존 draft/regrade/user-notes/report API를 재개발하지 않고 아래 실제 공백과 미검증 경계를 다룬다.

| 원본 항목 | 이번 로컬 증거 | 한계 |
| --- | --- | --- |
| CLASS-01, MAT-01, SEC-01 | 다른 learner/instructor/admin의 세션·메시지·PDF·SSE·리포트 거부, 현재 멤버의 정상 파일/SSE 접근, 멤버 제거 뒤 파일/turn/리포트/SSE 거부, 자료 연결 해제·소유자 탈퇴 자료 정리 뒤 SSE 거부 | 브라우저·실제 proxy·이미 열려 실행 중인 AI 스트림의 즉시 중단을 검증하지 않음 |
| AUTH-07, ADMIN-02 | 서로 다른 UserAccessGuard 객체가 공유 DB의 역할 변경·정지 commit을 다음 요청에서 반영 | 실제 다중 서버 배포·부하 검증은 미실행 |
| EXAM-02, SYS-02 | 동일/다른 requestId의 동시 제출이 한 attempt·답안 집합·채점 작업으로 수렴, 기존 동시 재채점/중복 worker 및 실패 결과 은닉 테스트를 MySQL에서 실행 | 실제 학생·유료 AI 없음 |
| MAT-03, SYS-03 | 실제 제한 큐 포화 시 응답/DB SUBMITTED와 미claim 상태 보존, 새 worker/scheduler의 재회수, 만료된 worker 결과 거절, 기존 자료/리포트 복구·lease·버전 검사를 합성 MySQL에서 실행 | 새 객체로 process-local 상태 손실을 모델링함. 실제 JVM kill, 운영 컨테이너 재시작·장애 주입은 미실행 |
| QA-02, QA-04 | 전체 BE build와 필수 정확한 head CI 결과는 PR에 기록 | 실제 메일 수신·운영 backup restore·실데이터 E2E·운영 부하는 별도 접근/승인 필요 |

## 재현과 수정

기존 코드는 멤버 제거 및 자료 연결 해제 후에도 소유한 ACTIVE 세션의 새 SSE를 200으로 열었다. 새 연결은 기존 세션 소유권/ACTIVE 검사 후 MaterialAccessService로 현재 자료 상태와 접근 grant를 확인한다. 오류는 기존 MATERIAL_NOT_FOUND(404)를 사용하고 emitter 등록 전에 거부한다.

기존 1분 process-local positive 권한 캐시는 다른 인스턴스의 역할 변경을 보지 못해 오래된 INSTRUCTOR 토큰으로 리포트 조회가 200이었다. 정지도 다른 guard 객체에서 정상 상태로 보였다. 인증 요청마다 User PK로 role/status scalar를 조회하여 현재 DB 상태를 적용하고 local invalidation 의존성을 제거했다. SUSPENDED는 기존 ACCOUNT_SUSPENDED(401), 탈퇴/누락/역할 불일치는 TOKEN_INVALID(401)를 유지한다. admin endpoint의 기존 DB guard 및 응답 계약은 유지한다.

변경은 인증 요청당 작은 PK 조회를 추가한다. DB 장애는 허용으로 처리하지 않는다. 이미 처리 중인 요청이나 외부 AI 전송을 소급 취소하는 정책은 별도 경계이며 이 변경의 보장이 아니다.

## 재실행

기본 H2/mock 전체 검사: `cd main-service && ./gradlew clean build -Dit.ai=false`.

MySQL opt-in: `RUNTIME_REGRESSIONS_MYSQL=true`로 SessionAccessRevocationJpaTest, ExamFailureSecurityJpaTest, ExamGradingRecoveryJpaTest, MaterialFailureJpaTest, ReportJpaTest를 실행한다. URL은 loopback:33316의 각 `runtime_*_synthetic` schema로 고정되어 있다. schema는 테스트 create-drop으로 재생성하므로 실행 전 task가 소유한 전용 MySQL datadir/port를 확인하고 합성 schema만 준비해야 한다. 실제 운영 연결 문자열을 받지 않는다. 외부 AI는 mock을 사용한다.

필수 CI와 최종 SHA·통과/실패/미실행 수는 해당 PR에 기록한다. DEV PDF 65/68 조사·복구, dueAt 자동 강제 마감, 타팀 #469 AI 동작 변경은 포함하지 않는다.
