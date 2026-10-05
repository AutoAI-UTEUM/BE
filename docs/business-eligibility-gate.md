# 신규 연령·보호자 대기 계정의 업무 접근 차단 (#478)

업무 API·AI·SSE·턴 저장은 같은 현재 계정 자격 규칙을 사용한다. #519에서는 2026-10-05 사용자 승인으로 KST 날짜와 연도차 기준을 추가했다. 보호자 확인 성공·실제 provider·첫 출시 전체 완료를 뜻하지 않는다. [승인 기준·수정 요청](birthdate-policy-and-correction.md).

## 현재 접근 계약

| 현재 계정 | 업무 API·파일·SSE·기존 AI 전송 게이트 |
| --- | --- |
| 정지·탈퇴·누락·JWT 역할 불일치 | 기존 계정/역할 오류 유지 |
| `LEGACY_EXEMPT` | 기존 이용 유지; 소유권·멤버십·역할 검사 계속 적용 |
| `NEW_SIGNUP`, 이메일 확인 증거 없음 | `EMAIL_VERIFICATION_REQUIRED`(403) |
| `NEW_SIGNUP`, 이메일 증거 있음, 유효 DOB의 KST 연도차 ≥15 | 보호자 불필요; 나머지 역할·소유권·필수동의 조건 유지 |
| `NEW_SIGNUP`, 이메일 증거 있음, 연도차 ≤14 또는 DOB 미확인, `UNKNOWN` | `AGE_VERIFICATION_REQUIRED`(403) |
| `NEW_SIGNUP`, 이메일 증거 있음, 연도차 ≤14 또는 DOB 미확인, `MANUAL_PENDING` | `GUARDIAN_VERIFICATION_PENDING`(403) |

이메일 `VERIFIED`와 확인 시각이 모두 있어야 이메일 증거가 유효하다. 사용자 입력 DOB로 승인된 보호자 대상 여부를 계산할 수 있지만 법적 보호자 관계나 인증된 연령 증거를 생성하지 않는다. 생일 전후와 무관하게 올해 14세에 해당하는 출생 연도도 대상에 포함한다. `UNKNOWN`·`MANUAL_PENDING` 저장값은 연도차 ≥15가 되어도 변경하지 않는다. `LEGACY_EXEMPT`는 별개의 이용 예외이며 상태나 증거를 변경하지 않는다.

로그인·refresh·이메일 확인·본인 조회/정보/비밀번호/설정/아바타/동의/탈퇴·정책 문서·health의 기존 예외는 유지한다. 본인 DOB 수정 요청 접수/조회도 인증 후 이용 가능하며 접수는 DOB·이용 자격을 바꾸지 않는다. 필수동의 설정과 기존 소유권 검사를 우회하는 flag나 공개 cohort 변경 API는 추가하지 않는다. FE는 서버의 실제 업무 응답과 위 403을 처리해야 한다. FE 구현·배포는 이 BE 단위에 포함하지 않는다.

## 구현 경계

- 기존 `EmailVerificationGate`의 계정 상태 검사 이후 공통 이메일·연령 규칙을 적용한다. 기존 MVC 인터셉터와 모든 기존 AI 직전 guard가 이 규칙을 사용한다. PDF 읽기·외부 파일 업로드·추출·캡션·개요·시험 초안/채점·퀴즈 채점·리포트·대화 요약, quota 검사를 거치는 대화/assessment/diagnosis/turn 경계에 적용한다. quota 비활성이나 ADMIN 역할도 규칙을 생략하지 않는다.
- `UserAccessGuard.check`는 역할·활성 상태만 검사하여 본인 관리와 확인 절차를 유지한다. 별도 `checkBusiness`는 DB scalar projection 한 번으로 현재 역할·상태·이메일 증거·cohort·연령 상태·DOB를 검사한다. DOB는 이 내부 projection에서만 사용하며 외부 AI/사용자 DTO에 추가하지 않는다. SSE 패킷별 계정 조회 수를 추가하지 않고 기존 조회를 확장한다.
- `SessionStreamAccessGuard`는 기존 `REQUIRES_NEW` 경계에서 업무 검사를 사용한다. 연결·델타·heartbeat·terminal 전달과 기존 턴 완료/후처리/JSON 응답 경계에서 오래된 managed User나 JWT가 현재 차단 상태를 우회하지 못한다. 턴 저장과 사용자 취소 partial은 `TurnPersistenceService`의 기존 사용자 행 잠금·current-read refresh 이후 같은 eligibility 규칙을 검사하여 AI 결과·페이지 상태·memory 저장 전에 거부한다. 성공 commit 후 재시도 방지 규칙은 유지한다.
- standalone `AgeEligibilityGate`도 같은 `BirthdatePolicy` KST 연도 규칙을 사용한다. 생일 계산·업체 어댑터·승인 담당자 권한은 추가하지 않는다. V60은 DOB 수정 요청 접수 테이블만 추가한다. health 검사와 삭제/개인정보 정리 작업에는 이용 게이트를 새로 연결하지 않는다.

## 합성 검증과 운영 경계

수정 전 실제 JPA/JWT 합성 테스트42개 중 업무/API·파일·AI·SSE 경계30개가 실패했고 본인 조회와 기존 예외12개는 통과했다. 수정 후 동일42개와 이메일 오류 우선순위6개를 검사한다. 모든 역할과 두 신규 상태, PDF/AI 호출0, quota 비활성/ADMIN, owner guard, SSE 중단·upstream 취소, 오래된 사용자 snapshot의 committed 상태 우회 방지를 다룬다. 실제 이메일 확정 회귀는 확정 후에도 신규 연령 차단이 남는 계약을 검사한다.

기존 업무 회귀 fixture는 명시적인 test-only `legacyVerified`로 구분한다. 이메일만 확인하는 `verified` helper는 신규 승인으로 바꾸지 않는다. 기존 완료 경계의 합성 DB 변경에 UNKNOWN/MANUAL_PENDING을 추가해 마지막 델타 뒤 완료, JSON 완료, 사용자 취소 partial, commit 뒤 응답·후처리·memory 차단, 저장↔변경 경합, 오래된 snapshot을 검사한다. 새 완료 시나리오는 처음에는 응답 차단 뒤 AI 메시지가 저장되던 누락10개를 검출했고, 사용자 행 잠금 안의 저장 전 eligibility 검사로 보완한다. 이 SQL 변경과 legacy 복구는 로컬 test fixture에서만 실행하며 production 전이 기능이 아니다.

전체 build와 별도 loopback MySQL의 정확한 결과·미실행 항목은 PR에 기록한다. 로컬 MySQL은 소유한 synthetic schema와 127.0.0.1:33316만 허용하며 실제 DEV/운영 계정 상태·DB·provider·발송·유료 AI·학생 데이터·배포를 변경하지 않는다. 신규 실제 승인/거절/철회/만료 및 목적별 AI 동의/철회 epoch, 증거 보존·담당 권한·관리자 DOB 정정 증빙·실행 절차는 후속 결정과 구현으로 남는다. 날짜/시간대/연도 기준은 #519의 사용자 승인으로 확정됐다. 기존 예외 정책을 약화하거나 신규 계정을 임의로 기존 예외로 승격하는 방식으로 해결하지 않는다.
