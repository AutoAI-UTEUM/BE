# 출시 연령·동의·철회와 BE–AI 계약 초안

상태: **설계 초안, API/차단/작업 취소 구현 완료 아님**. 기준 develop `0d9c14f`.
2026-10-03 사용자 결정: 만 14세 미만 포함, 초기 보호자 확인은 수동으로 진행하고
향후 업체를 교체할 수 있는 경계를 둔다. 신규 가입에는 생년월일을 받고 기존 계정은
재입력하지 않는다. 기존 UNKNOWN을 연령·보호자 VERIFIED로 바꾸지 않는다.
수동 확인 담당 권한·증빙·승인/거절/철회·만료, 연령 판정 기준일과 증빙 보존기간은
미정이다. 미구현 확인을 성공으로 처리하지 않는다.

## 현재 구현과 담당 범위

| 요청 | 현재 근거 / 연결 | 다음 담당 경계 |
| --- | --- | --- |
| NOTE-04 세션 퀴즈 100건 이후 | #475 / PR #481에 page 계약·요청/응답 예시·정렬·마지막 페이지를 구현하고 테스트함. develop 미반영 | BE는 endpoint, FE는 별도 세션 퀴즈 목록 page 순회. FE #220의 노트 어댑터와 별개 |
| AUTH-01 연령·보호자·메일 확인 | User/가입 DTO에 연령·보호자 확인 근거가 아직 없음. #471은 이메일 소유확인, #478은 연령·보호자 상태와 이용 gate | BE가 신뢰된 확인 결과와 gate 소유. 보호자 방식·기존 계정 전환은 결정 필요 |
| DATA-01/06 동의 버전·철회 | #415에 LOCAL/Google 가입 동의 버전 기록과 현재 버전 pending 조회가 이미 있음. 외부 AI 목적별 동의·철회 epoch/cancel은 아직 없음 | #415 기존 동의 재사용, #478 상태/API, #479 Spring worker·늦은 결과 회귀, #477 파일 정리 |
| AI 완료·실패·취소와 재시도 | #469 / PR #470의 AI timeout·관측성 작업을 재개발하지 않음. Spring에서 `retryable=true`를 자동 재시도로 소비하지 않는 경계가 남음 | #479에서 DB 상태와 결과·취소·중복 저장·명시 재시도 계약 검증. timeout 실패를 성공으로 바꾸지 않음 |
| MAT-01 session54/55 PDF | 앞선 진단·추가 복구 중단 결정이 있음 | 이 작업에서 조회·진단·복구를 다시 실행하지 않음 |

## FE 조회 계약 제안

아래 enum·endpoint·오류는 **제안 값**이며 현재 사용 가능한 API로 간주하지 않는다.
현재 `UserStatus=ACTIVE/SUSPENDED/DELETED`와 가입자의 정책 동의 이력을 별개로 유지한다.
Google 계정/이메일 확인은 보호자 확인 근거가 아니다.

제안 endpoint: `GET /api/users/me/access-state` (본인 인증, `Cache-Control: no-store`).

```json
{
  "success": true,
  "data": {
    "accountStatus": "ACTIVE",
    "emailVerification": "VERIFIED",
    "ageGroup": "UNDER_14",
    "guardianVerification": "PENDING",
    "aiConsent": "PENDING",
    "aiAccess": "PENDING",
    "consentEpoch": 3,
    "blockingReasons": ["GUARDIAN_VERIFICATION_REQUIRED"],
    "requiredPolicyVersions": [{"type": "TERMS", "version": "<현재 버전>"}]
  }
}
```

| 필드 | 제안 값 / 의미 |
| --- | --- |
| emailVerification | UNKNOWN / PENDING / VERIFIED. 기존 계정에 실제 확인 근거 없이 VERIFIED를 소급 기록하지 않음 |
| ageGroup | UNKNOWN / UNDER_14 / AGE_14_PLUS. 입력·판정 근거는 별도 결정이며 UNKNOWN은 확인 완료가 아님 |
| guardianVerification | UNKNOWN / NOT_REQUIRED / PENDING / VERIFIED / REVOKED. VERIFIED는 신뢰된 실제 확인 결과로만 전환 |
| aiConsent | PENDING / GRANTED / REVOKED. 특정 목적과 동의 문서 버전·시각을 서버가 기록 |
| aiAccess | PENDING / ACTIVE / REVOKED. 필수 확인/동의 누락은 PENDING, 철회는 REVOKED. ACTIVE는 서버가 모두 검증한 결과 |
| consentEpoch | 사용자 동의·이용 권한 변화마다 증가하는 서버 값. 클라이언트가 활성화를 지정할 수 없음 |

누락/형식 오류는 400 `VALIDATION_FAILED`와 필드 오류를 반환하는 방향을 제안한다.
필요한 연령 선언 자체가 없으면 `AGE_INFORMATION_REQUIRED`, 확인 전에는
`EMAIL_VERIFICATION_REQUIRED`, `GUARDIAN_VERIFICATION_REQUIRED`,
`POLICY_CONSENT_REQUIRED`, `AI_CONSENT_REQUIRED`, 철회 후에는
`AI_CONSENT_REVOKED`를 403 차단 이유로 제안한다. 타인 자료 은닉 404 계약은 유지한다.
실제 코드·OpenAPI에 추가되기 전 FE는 이 값을 완료된 계약으로 배포하지 않는다.

연령 미확인/보호자 대기 중에도 본인 상태·확인 절차·동의·탈퇴 절차를 진행할 경로는 필요하다.
그 외 학습/파일/SSE/리포트와 외부 AI gate의 정확한 허용 경계, AI 철회 후 AI 없는 기능을
계속 이용할 수 있는지는 목적별 동의 정책과 함께 결정해야 한다. 보호 장치를 꺼서 열지 않는다.

## BE–AI 전송·철회 책임 제안

BE가 사용자·역할·자료 접근권·현재 동의/연령 상태의 최종 책임을 가진다.
가입 HTTP 요청에서 한 번 확인하는 것만으로 background 작업의 전송을 허용하지 않는다.

1. enqueue 전에 DB의 현재 권한·필수 확인·동의 버전을 확인하고 사용자와 epoch를 작업에 저장한다.
2. worker claim 뒤 **외부 요청/파일 업로드 직전**에 현재 DB 상태와 epoch를 다시 확인한다.
3. 철회 DB 트랜잭션은 epoch 증가와 대기 작업 취소 표시, 삭제 의도를 함께 저장한다.
4. 진행 작업은 커밋 후 실제 취소를 전달하고 재시도도 현재 epoch를 다시 확인한다.
5. 늦은 성공 응답은 저장 직전에 현재 상태/epoch를 다시 확인한다. 일치하지 않으면 결과를
   정상 완료/DB artifact로 저장하지 않고 취소 상태로 기록한다. 이미 생성된 외부 파일은 정리 대상으로 추적한다.

AI에는 내부 인증된 요청 ID·작업 ID·취소/epoch 연계에 필요한 값만 전달하는 방향이다.
생년월일, 보호자 연락처, 확인 증빙 원문은 AI에 전달할 필요가 없다. 기존 AI 코드의
timeout·retryable 계약은 #469 담당자와 맞추며 본 문서가 AI의 취소 API 구현을 가정하지 않는다.
취소 전달 실패와 외부 파일 정리 실패는 durable intent/retry 상태로 관측해야 한다(#477).
로컬 파일의 삭제 시점은 아직 정하지 않았고 보존 정책 미설정은 `POLICY_PENDING`으로 구분하는 방향이다.

## 검증할 시나리오 (아직 실행 아님)

| 조건 | 요구 결과 |
| --- | --- |
| 연령/필수 동의 누락, 보호자 확인 전 | 학습/외부 AI gate 거부. mock AI 요청/업로드 호출 0 |
| 큐 대기 중 철회 | claim/전송하지 않고 취소. 재시작 후에도 다시 보내지 않음 |
| 전송 직전 철회와 경합 | 전송 경계의 현재 상태/epoch 검증으로 차단 또는 이미 시작된 요청 취소 추적 |
| 실행 중 철회 후 늦은 AI 성공 | 결과 저장 거부, DB 작업 취소 유지, 생성된 외부 파일 정리 의도 남음 |
| 실패 작업의 재시도 전에 철회 | stale epoch를 재사용하지 않고 재시도 차단 |
| 중복 결과/worker 재시작/lease 만료 | 한 번의 정상 상태 전이·중복 저장 방지, 미확인 외부 결과를 성공 stub으로 처리하지 않음 |

실제 메일 수신, Google/보호자 업체, 유료 AI, 학생 데이터, 운영 백업 복구는 이 초안의 검증 대상이 아니다.

## 결정 요청과 권장 경계

| 결정 | 선택지 | 권장안 |
| --- | --- | --- |
| 보호자 확인 근거 | **확정: 초기 수동 확인**, 향후 업체 adapter 교체 가능 | 운영 담당자 권한·증빙 감사·승인/거절/철회·만료 기준 확정 전 VERIFIED 전환을 활성화하지 않음 |
| 연령 최소 입력·기존 계정 | **확정: 신규 생년월일 입력, 기존 재입력 생략** | 기존 UNKNOWN 유지와 명시적 legacy 적용 범위를 구분. 생년월일 누락을 신규 가입 성공으로 처리하지 않음. 판정 기준일·시간대는 추가 결정 필요 |
| 파일 보존기간 | **확정: 보존기간 뒤 삭제**, 구체 일수 미정 | durable 의도·대상별 완료/실패 추적. 기간 미설정은 POLICY_PENDING이며 물리 삭제 worker 활성화하지 않음 |
| 강사 소유 강의실 | **확정: 탈퇴 시 소유 강의실 종료** | 기존 종료 상태·권한 회수 규칙에 연결. 종료 hook 구현·경합 검증 전 완료 표시하지 않음 |

백업 복원 재적용에는 복원하는 DB 바깥의 삭제 journal/manifest가 필요하다.
같은 DB의 outbox만으로 오래된 백업 복원 후 삭제를 보장하지 않는다. #408 운영 절차와
연계하며 새로운 클라우드 자원·권한·실제 운영 복원을 임의 실행하지 않는다.

develop merge/push는 DEV 이미지 빌드·컨테이너 교체·Flyway 실행을 수반하므로 부모가 요청한
추가 DEV 배포 승인 답변을 기다린다. main/prod는 대상이 아니다.
