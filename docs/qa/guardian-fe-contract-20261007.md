# TEAM_REVIEW FE 계약 보완 — 2026-10-07

원요청 `1557212030770094091`의 최초 회신 채널·담당자 세대/웹 선언 범위·서버 진입 신호를 보완한다. 기준은 PR533 exact `28e265397dc52667fd5e7f98f1e782ba2ddafef7`, 관련 이슈는 #526·#479다.

브랜치 `feature/526-guardian-fe-contract`: 보호자 FE 최초 진입을 서버 판정으로 제공하고 회신 채널·세대 시작·선언 범위를 DTO에 연결한다.

기준 코드에서 replyChannel은 양식 문자열에만 있었고, generationStartedAt·declaredScopes는 DB에만 있었다. 신청 대상 검사는 서버에 있었으나 접수 전 조회는 없었다. [최종 FE 계약](../guardian-team-review-fe-contract.md)에 Entry/View/Detail과 nullable·권한·기한 의미를 기록했다. OpenAPI는 실제 record와 controller annotation에서 생성되며 [API 명세](../api-spec.md), [화면 매핑](../screen-api-map.md)을 함께 갱신했다.

| 회귀 | 검증한 경계 |
| --- | --- |
| 인증된 최초 진입 | 익명 거절, 이메일 미확인 본인 조회 허용, no-store/no-referrer, 새로운 신청·개인정보·승인 생성 없음, 업무 API는 계속 403 |
| 서버 대상 판정 | KST 12월31일/1월1일 연도 차이 14→15, LEGACY_EXEMPT 보존, 누락/미래 DOB는 BIRTHDATE_REQUIRED, DOB·ID 응답 제외 |
| 기존 신청 복원 | 진행 중/승인 중 새 신청 금지, SERVICE 승인과 EXTERNAL_AI 승인 분리, 정확한 승인 만료 경계에서 기존 차수 EXPIRED |
| 구조화된 회신·선언 | Entry/View/Detail 회신 채널, 담당자만 generationStartedAt·배열 declaredScopes, DECLARED는 승인 아님, 웹 선언과 다른 수동 확인 범위 409 |
| 세대·파기 | 링크 재발급은 세대 시작 유지·선언 초기화, 철회는 선언 제거, 새 차수만 새 시작 시각 |
| 정책 불일치 | 오래된 신청의 채널 null·forms 빈 값, 현재 정책 채널을 과거 신청에 대입하지 않음 |
| 미준비 정책 | 대상 신호만 반환, 신청 불가·채널/신청 null, request/event/operation/mail 정리 경로 접근 없음 |

JDK21 / Gradle9.5.1, offline, H2 in-memory, mock AI·mail로 `test --tests '*GuardianTeamJpaTest' --tests '*GuardianTeamEntryTest'`를 실행했다. 최종 **33/33 PASS, failures0/errors0/skipped0**이며 기존 26건과 신규 7건이다. 전체 Java test source도 컴파일됐다. 필수 전체 build 및 AI CI는 게시한 정확한 head에서 따로 확인한다.

설정·정책·DB migration·FE 저장소는 변경하지 않는다. TEAM 기본 OFF와 미확정 정책 차단, 기존 이메일/보호자 업무·파일·SSE·외부 AI 게이트, 본인 신청 철회·같은 idempotency 본문 재시도는 유지한다. 실제 FE 브라우저·배포 서버·메일 수신·AI 업체·운영 데이터·백업 복구 인수는 미실행이다. 합성 기간은 운영 정책으로 채택하지 않았다.
