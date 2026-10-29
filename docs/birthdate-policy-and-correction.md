# KST 생년월일·연도 기준과 관리자 수정 요청 (#519)

2026-10-05 05:50:09 UTC 제안에 대해 사용자가 06:35:05 UTC “진행”으로 승인했다. 아래 기준은 날짜·보호자 대상 분류와 수정 요청 접수에 한정한다. 보호자 업체·관계 증빙·승인 흐름과 실제 운영 활성화는 별도다.

## 날짜와 업무 이용 기준

- 가입·수정 요청 날짜는 `Asia/Seoul`의 오늘 이하, 기존 SQL DATE 입력 범위(연도 1~9999)여야 한다. 미래일·누락은 `VALIDATION_FAILED`(400), 형식 오류는 기존 `MALFORMED_REQUEST`(400)다. 신규 Google DOB 누락은 기존 `SIGNUP_REQUIRED`(409)다.
- `KST 현재 연도 - 출생 연도 <= 14`는 보호자 대상이다. 올해 14가 되는 출생 연도 전체를 포함한다. 생일 전후·윤년 생일 보정으로 대상을 제외하지 않는다.
- 연도차 `>= 15`는 보호자 불필요다. KST 새해 1월 1일 00:00부터 같은 저장 DOB의 분류가 달라질 수 있으며 상태 backfill·인증 성공 기록을 만들지 않는다.
- `NEW_SIGNUP`은 먼저 이메일 VERIFIED와 확인 시각이 필요하다. 보호자 대상·DOB 미확인은 UNKNOWN이면 `AGE_VERIFICATION_REQUIRED`, MANUAL_PENDING이면 `GUARDIAN_VERIFICATION_PENDING`(403)이다. 비대상 계정도 역할·계정 상태·소유권·멤버십·기존 필수동의 제한을 유지한다.
- `BirthdatePolicy`와 주입된 Clock을 가입, 업무 API/AI, 현재 SSE projection, 잠금 안의 턴 저장에 공통 적용한다. 서버/사용자 기본 시간대는 이 규칙을 바꾸지 않는다. 기존 `LEGACY_EXEMPT` 이용 예외는 유지한다.
- `UNKNOWN`·`MANUAL_PENDING`는 인증 증거 상태로 그대로 둔다. DOB 입력, Google 로그인, 수정 요청, 문자 소유 확인은 법정대리인 관계나 보호자 동의 완료 증거가 아니다.

신규 LOCAL·Google 가입의 기존 `PolicyService.validateSignup`, 필수동의 설정, 버전별 동의 원장을 유지한다. 연도 기준을 만족해도 가입 필수동의를 생략하거나 이메일 확인을 건너뛰지 않는다. 기존 Google subject 로그인은 제출 DOB를 검증·반영하지 않으며 기존 값을 변경하지 않는다. DOB는 User/Signup 응답이나 외부 AI DTO로 전달하지 않는다.

## 관리자 수정 요청 접수

| API | 권한·결과 |
| --- | --- |
| `POST /api/users/me/birthdate-correction-requests` | 활성 로그인 본인. `{ "requestedDateOfBirth": "2011-12-31" }` 접수; 이메일/보호자 대기 계정도 가능 |
| `GET /api/users/me/birthdate-correction-requests` | 본인 요청 조회. 접수 전 `data: null`; 소유자 ID를 입력받지 않음 |
| `GET /api/admin/birthdate-correction-requests?page=0&size=20` | 현재 DB ADMIN의 PENDING 목록. 요청시각→ID 오름차순, size 1~100 |
| `GET /api/admin/birthdate-correction-requests/{id}` | 현재 DB ADMIN의 상세 조회. 탈퇴 요청의 DOB는 null |

접수 응답은 `{id,userId,requestedDateOfBirth,state,requestedAt}`이며 state는 `PENDING`이다. 생년월일을 즉시 변경하거나 `MANUAL_PENDING`·guardian 승인으로 승격하지 않는다. 저장된 현재 DOB가 보호자 대상이면 성인 날짜를 요청해도 이용 제한은 계속된다.

동일 사용자·동일 날짜 재요청은 최초 ID/시각을 반환한다. 다른 날짜의 대기 요청은 `BIRTHDATE_CORRECTION_PENDING`(409), 현재 DOB와 같은 요청은 `VALIDATION_FAILED`(400)다. 관리자에게 없는 ID는 `BIRTHDATE_CORRECTION_NOT_FOUND`(404)다. 관리자 수정·승인 쓰기 API는 제공하지 않는다. 요청 반려·정정 증빙·실제 적용 절차가 정해지기 전에는 접수를 승인 완료로 표시할 수 없다.

응답은 `Cache-Control: no-store`다. 관리자 URL·메서드·현재 DB 권한 검사와 기존 본문 없는 감사 로그를 사용한다. 감사 로그에는 요청 ID/행위/상태만 기록하고 DOB·본문을 추가하지 않는다. 요청 DTO·내부 projection의 문자열 출력도 마스킹한다. 추가 전화번호·증빙·자유서술 개인정보는 수집하지 않는다.

## 저장과 회귀 검증

V60은 `birthdate_correction_requests` 접수 테이블만 생성한다. user당 유일 행과 PENDING/DOB 필수, WITHDRAWN/DOB NULL CHECK 및 대기 큐 인덱스가 있다. 기존 User DOB·cohort·메일/연령 증거를 변경하지 않는다. 요청은 독립 트랜잭션의 첫 current User 잠금으로 직렬화한다. 탈퇴는 같은 User 잠금 아래 요청 DOB를 제거하고 WITHDRAWN으로 바꾸며 rollback 시 함께 복구된다. 대기/종료 metadata 보존기간은 아직 미정이다.

합성 Clock/JPA/JWT·mock Google·AI로 KST 자정/연도 전환/14·15/윤년/LOCAL·Google/필수동의·메일/기존 계정/자료·파일·SSE·턴 저장과 접수 중복/권한회수/탈퇴 경합을 검증한다. 테스트의 JWT 발급은 실제 시각을 사용해 만료 영향을 분리하고 정책 Clock은 고정한다. 전체 결과와 실제 loopback MySQL의 V1→V60·CHECK·잠금 검증은 PR에 기록한다.

FE 화면·배포, 실제 관리자 권한 부여·계정 정정, 보호자 관계 확인·업체·SMS, 실제 메일 수신·유료 AI·실학생 데이터·운영 migration·물리 삭제·백업 복구를 실행하지 않는다. #519는 보호자 확인 전체 완료나 develop 배포 승인이 아니다.
