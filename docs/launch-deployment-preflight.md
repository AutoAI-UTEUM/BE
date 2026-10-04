# 출시 BE 후보의 배포 사전 점검과 복구 (#479)

브랜치 한 줄: `feature/479-deployment-preflight` — 신규 가입 제한과 기존 계정 이용을 보존하는 배포 순서·설정 점검·복구 조건을 정리한다.

이 문서는 후속 DEV 배포를 준비하는 검토 자료다. 서버 조회·설정 변경·배포·실메일 수신·운영 백업 복원을 실행했다는 기록이 아니다. develop push를 승인받더라도 `.github/workflows/deploy-dev.yml`이 두 서비스 이미지 빌드/push, DEV 컨테이너 교체, nginx 재시작과 Main 시작 시 Flyway를 실행하므로 해당 후보의 배포 영향까지 승인 범위에 들어가야 한다. main/prod 반영은 이 작업의 범위 밖이다.

## 1. 검토할 정확한 버전

2026-10-04 사전 점검 기준 upstream은 `ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938`이다. PR #500의 변경은 `ai-service/tests/` 아래 세 파일이며 서비스 런타임을 바꾸지 않는다. 통합 후보는 이 변경과 이미 merge된 #496/#497/#499를 보존한다. 최종 배포 검토에서는 최신 upstream, 실제 승인 대상 후보 SHA, 그 SHA의 필수 Main/AI CI, 실행 중인 Main/AI image tag와 RepoDigest, 실제 FE build SHA를 다시 기록한다. 소스 브랜치 SHA를 서버 배포 증거로 대신 쓰지 않는다.

FE develop `1b6987d`의 소스 검토에서는 신규 가입 요청의 DOB 입력, `/verify-email` 화면 및 확인 API 연동, 사용자 응답의 이메일 확인 필드 보존이 없다. 해당 FE 소스와 이 BE 후보만으로 신규 가입·학습 동선이 완성되지 않는다. 이는 실제 배포된 FE build를 측정한 결과는 아니다. [가입·이메일 계약](email-verification.md), [DOB 기반](birthdate-guardian-foundation.md), [기존 계정 예외](legacy-account-access.md)를 FE 담당자와 대조하고 실제 지원 build 및 합성 검증 결과를 받아야 한다. BE는 누락 DOB를 허용하거나 신규 계정을 기존 예외로 지정해 이 차이를 우회하지 않는다.

## 2. 배포 전 조건

| 확인 대상 | 필요한 증거와 중단 조건 |
| --- | --- |
| 코드·CI | 승인 대상 SHA가 검토한 기능 브랜치를 포함하고 필수 Main/AI CI가 같은 head에서 성공해야 한다. 실패·진행 중·다른 SHA의 CI는 성공으로 대체하지 않는다. |
| FE | 신규 LOCAL/Google DOB·현재 정책 동의, 이메일 확인 링크 화면·명시적 POST·상태 재조회·재발급, legacy 표시와 신규 403 안내가 지원되는 build를 확인한다. FE 미준비 상태에서는 후속 BE 배포를 보류한다. |
| 메일 | provider/from/base URL/지역과 수신 경로를 담당자가 확인한다. `enabled=false` 또는 logging 수락은 실메일 수신 증거가 아니다. 실제 수신 시험은 승인된 합성 주소와 비용·권한 범위에서 별도로 수행한다. 확인 경로가 없으면 신규 사용자는 PENDING으로 계속 차단된다. |
| DB | 현재 Flyway 성공/실패 버전과 V53–V59 적용 순서·checksum을 확인한다. 미리 적용된 버전이 있으면 checksum과 실제 schema를 대조한다. 실패 이력 삭제, repair, out-of-order 또는 VERIFIED/DOB/guardian 성공 백필로 진행하지 않는다. |
| 백업 | 새 migration 직전 승인된 DB 백업의 시각·크기·압축 무결성·hash와 접근 제한을 확인한다. DB 덤프에는 원본 PDF/렌더가 없으므로 파일 보관·복구 증거를 별도로 준비한다. 예전 백업의 존재나 로컬 합성 복원 검사를 실제 운영 복구 완료로 표시하지 않는다. |
| 기존 계정 | V58 직전에 존재한 계정의 cohort가 migration 후 LEGACY_EXEMPT이고 기존 DOB/이메일/guardian 증거가 보존되는지 검증할 합성 사례를 준비한다. 정지·탈퇴·역할·소유권 제한은 예외에 포함되지 않는다. |
| 신규 계정 | LOCAL/새 Google 가입이 NEW_SIGNUP/PENDING으로 시작하고 이메일 확인 전 업무 API·파일·SSE·외부 AI가 거부되는지 확인한다. Google 기존 subject 로그인은 DOB 재입력을 요구하지 않는다. |
| 복구 이미지 | 아래 5절의 조건을 만족하는 불변 SHA 이미지와 설정/Compose 사본을 준비한다. 신규 계정 gate가 없는 옛 이미지로의 복귀를 일반 롤백으로 승인하지 않는다. |
| 운영 권한 | 실제 서버 접근·메일 시험·백업/복원·컨테이너 교체에 필요한 접근 및 해당 후보 배포 승인을 확인한다. 이 문서 작성이나 로컬 테스트가 외부 작업 승인을 넓히지 않는다. |

보호자 정상 경로는 웹 동의 → SMS 확인, 실패·불일치·분쟁만 수동 접수로 승인됐다. 아직 실제 SMS 업체/연결·법적 보호자 관계 증거·담당자 권한·보존/철회 정책과 전체 age 경계는 완성되지 않았다. 이 후보의 PHONE_CONFIRMED는 전화 통제 증거이며 연령/보호자 이용 승인 상태가 아니다. 보호자 foundation 배포와 만14세미만 포함 출시 완료를 구분한다.

## 3. 실제 설정 대조

설정은 승인된 운영자가 실제 컨테이너의 **유효 값**과 대조한다. `.env` 원문, `docker inspect` 전체 Env, `docker compose config` 전체 출력, JWT/DB/내부 토큰/암호화 키/개인 메일 주소는 이슈·로그·증거에 올리지 않는다. 증거에는 활성 여부·provider 종류·base URL의 승인된 origin·키의 유지 여부와 공개 가능한 digest/count만 기록한다.

| 설정/경계 | 현재 소스 동작과 점검 |
| --- | --- |
| `EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED` | application/Compose 기본 false지만 실제 운영값은 별도 확인한다. 이미 켜진 필수 동의 설정을 끄지 않는다. 현재 `requiresConsent=true`인 정책 버전과 FE의 실제 선택·기록이 맞아야 한다. |
| `EDUPILOT_MAIL_ENABLED`, `EDUPILOT_MAIL_PROVIDER` | 발송 기본 false. base Compose의 provider 기본 logging에 production override 파일이 ses 기본을 덮는다. DEV도 그 override 파일을 사용하므로 YAML 한 파일만 보고 provider를 단정하지 않는다. |
| `EDUPILOT_MAIL_BASE_URL` | base Compose 기본은 dev origin, production override 기본은 www origin이다. DEV 배포의 실제 값이 승인된 FE 확인 화면 origin인지 확인한다. 다른 환경의 링크가 발송되면 중단한다. |
| `EDUPILOT_MAIL_FROM`, `AWS_REGION` | 승인된 sender/domain/지역 및 provider 사용 가능 여부를 확인한다. 이 변경은 SES 권한·도메인·비용 설정을 만들지 않는다. |
| `EDUPILOT_MAIL_OUTBOX_ENCRYPTION_KEY` | 선택 설정이다. 지정하면 Base64 decode 32바이트여야 한다. 미지정 시 기존 JWT secret에서 HKDF-SHA256 메일 전용 키를 유도한다. 새 키를 기동 필수로 오인하지 않는다. 재시작·복구 이미지·여러 서버는 같은 유효 키를 유지한다. 키/JWT secret 변경 시 이전 미완료 payload 복호화가 실패할 수 있으므로 임의 생성·교체하지 않는다. |
| 이메일 gate·cohort | 이메일 gate를 끄는 배포 flag는 없다. V58의 LEGACY_EXEMPT는 기존 계정 이용 정책이며 확인 성공 증거가 아니다. 새 User와 migration 이후 SQL 기본값은 NEW_SIGNUP이다. |
| 일반 파일 30일 | `edupilot.deletion.general-files.retention-days=30`은 직접 일반 자료 삭제의 원본/렌더 기한만 기록한다. 30 외 값은 기동 실패다. 기한 기록만으로 물리 worker를 활성화하지 않는다. |
| 물리 삭제·guardian 웹/SMS | `edupilot.deletion.enabled=false`, guardian web 기본 false와 미연결 SMS provider를 유지한다. 현재 Compose는 이 두 기능의 활성화 환경변수를 Main에 전달하지 않는다. `.env`에 이름만 추가해 활성화됐다고 판단하지 않는다. 런타임 설정을 별도로 주입·수정하는 작업은 승인된 후속 범위에서 검토한다. |
| Google·CORS·AI | 기존 Google client ID, 허용 FE origin과 PUT, Main→AI 토큰/timeout 및 이미 승인된 AI 설정을 보존한다. 키 출력·권한 확장·유료 AI 호출로 사전 점검을 대신하지 않는다. |

## 4. 적용 순서와 관측

### FE 기본 OFF와 현재 공개 계약 신호

현재 인증 capability endpoint나 가입 계약의 활성 여부를 반환하는 공개 배포 flag는 없다. `/api/health`의 `data.status=UP`과 `/api/health/ready`의 DB/AI readiness는 가입·메일 준비 증거가 아니다. 공개 `/v3/api-docs`에는 SignupRequest의 필수 DOB와 이메일 확인 경로가 있지만 이는 schema 존재 증거이며 실제 수신·FE 준비·출시 승인 신호로 사용하지 않는다. 내부 AI `capabilities`는 인증 계약의 신호와 별개다.

최소 지원 방안은 별도 API 증설 없이 FE가 기본 OFF인 배포 설정으로 검토된 BE 계약/FE build 조합을 명시하는 것이다. 실제 적용 책임자는 승인된 SHA와 활성 시점을 함께 관리한다. OFF일 때 기존 화면/서버 계약을 보존하고 새 화면을 숨기는 것은 FE 단계의 호환 방안이다. **새 BE의 DOB·이메일 gate는 조건부로 꺼지지 않으므로 FE OFF 상태로 후보 BE만 배포하는 것은 허용 경로가 아니다.** 기존 계정 로그인과 신규 가입의 전환을 분리하고, 새 가입 요청을 오류 응답으로 시험하면서 자동 활성화하지 않는다.

| 배포 조합 | 의미 |
| --- | --- |
| 이전 BE + FE 새 흐름 OFF | 기존 계약을 유지하는 선반영 후보. 해당 FE build에서 기존 동선을 검증해야 한다. |
| 후보 BE + FE 새 흐름 OFF | 신규 DOB/이메일 UI가 없어 400/409/403이 남는다. 후속 BE 배포 조건을 충족하지 않는다. |
| 검토한 후보 BE + 대응 FE 흐름 ON | 승인된 동시 전환과 실제 메일 경로·합성 인수 조건이 필요하다. 연령/보호자 출시 완료를 뜻하지 않는다. |

FE가 서버별 자동 계약 선택을 꼭 요구하면, 현재 입력 필수 여부와 **구현 지원**을 알리는 좁은 읽기 전용 신호의 필요성을 계약 담당과 먼저 확인한다. 이 문서에서는 endpoint/응답 필드·새 서버 flag를 만들지 않는다. 신호를 추가하더라도 실제 NEW_SIGNUP DOB/이메일 요구를 정확히 표현해야 하고, DB gate의 대체 조건이나 실메일 수신/guardian 승인으로 소비하면 안 된다. 네트워크 오류·404·알 수 없는 revision에서는 임의 새 기능 활성화 없이 기존 안전한 동선을 유지하며 후보 BE 전환은 보류한다.

가입 동의는 현재 `GET /api/policies/current`의 `requiresConsent=true` 대상과 실제 `EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED`를 따로 대조한다. 대상이 0개일 때의 현재 코드 동작과 UNKNOWN/LEGACY 응답은 [API 명세](api-spec.md) 및 [전달 계약](qa/fe-auth-contract/README.md)을 사용한다. 법무 준비 상태·기존 계정 예외·현재 동의 완료를 capability나 health로 추정하지 않는다.

1. 실제 FE 지원 build와 실메일 확인 경로, 승인 대상 SHA·설정·CI·백업·복구 이미지 증거를 갖춘다. 기존 FE가 새 필드를 아직 보내지 않는 상태에서 BE만 먼저 적용하지 않는다. FE 선반영 가능 여부와 활성화 시점은 해당 팀이 검증해야 한다.
2. 승인된 migration 창에서 쓰기 유입과 기존 프로세스의 종료 경계를 통제한다. V58 cohort 경계를 기록할 때 migration 중 가입/기타 User INSERT가 섞이지 않도록 한다. 연속된 migration의 checksum과 적용 결과를 기록한다.
3. V53 신규 노트 멱등성 컬럼/고유키 → V54 암호화 outbox/메일 예약 → V55 이메일 증거/토큰 → V56 삭제 원장 → V57 DOB/접수 → V58 기존 cohort → V59 웹/SMS 접수 순서를 따른다. V53은 수정된 중복을 포함한 기존 노트를 보존하고 신규 쓰기에 멱등성을 적용한다. V54 과거 메일 시도 예산/QUEUED 실패 표시는 기존 데이터 영향을 가진다. 빈 DB에서의 성공만으로 실제 데이터 migration 안전을 판단하지 않는다.
4. 승인한 후보 이미지로 Main/AI를 실행하고 정확한 두 tag/RepoDigest, Flyway 성공/실패와 health/readiness를 확인한다. `UP`만으로 가입·메일·기존 계정 예외가 검증됐다고 표시하지 않는다.
5. 승인된 전용 합성 계정으로 기존 예외 이용/정지·타인 거부, 신규 DOB/동의 오류, 이메일 PENDING 403, 링크 GET 비소비·POST 1회 확정·재발급·상태 재조회, refresh 후 동일 동작을 확인한다. 실제 수신과 API 확정은 별도 결과로 기록한다. 외부 AI 전송 검증에는 mock/허가된 환경을 사용한다.
6. 메일 READY/CLAIMED/SENDING/UNKNOWN/FAILED의 상태별 집계와 worker 회수를 살핀다. UNKNOWN은 성공이나 무조건 재발송 대상이 아니다. 삭제 원장은 POLICY_PENDING/일반 30일 기한을 확인하고 실제 물리 삭제·SMS 확인 성공·연령 승인 검증은 이 배포에 포함하지 않는다.

실서버에서 사용할 승인된 조회 경로로 다음 **집계만** 대조할 수 있다. 아래 SQL은 문서 작성 중 실행하지 않았다. migration 성공 후 해당 schema에서 운영자가 실행하며 ID·이메일·파일키·본문·token은 조회하지 않는다.

```sql
SELECT version, description, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT access_cohort, status, email_verification_state, COUNT(*) AS users_count
FROM users GROUP BY access_cohort, status, email_verification_state;
SELECT COUNT(*) AS invalid_verified_evidence
FROM users
WHERE (email_verification_state = 'VERIFIED' AND email_verified_at IS NULL)
   OR (email_verification_state <> 'VERIFIED' AND email_verified_at IS NOT NULL);
SELECT status, COUNT(*) AS outbox_count FROM email_outbox GROUP BY status;
SELECT status, COUNT(*) AS intent_count FROM deletion_intents GROUP BY status;
```

cohort 집계만으로 migration 전 존재한 개별 계정과 migration 후 생성한 계정의 구분까지 증명할 수 없다. 그 조건은 전용 합성 사례와 migration 전후 자료로 확인한다. 증거 오류·실패·FE 불호환·확인 링크 미수신·신규 제한 누락 시 진행을 멈추고 원인을 해결한다. 제한을 끄거나 신규 계정을 LEGACY_EXEMPT/VERIFIED로 변경하지 않는다.

## 5. 복구와 롤백 조건

- 코드 rollback과 DB restore는 다른 작업이다. V53–V59의 schema/감사/확인/cohort/삭제 원장을 제거하거나 down migration/repair로 되돌리는 절차는 제공하지 않는다. old 코드가 추가 컬럼을 무시하고 기동한다는 사실만으로 안전한 rollback을 판단하지 않는다.
- 복구 이미지는 현재 DB의 cohort와 확인 증거를 읽고 **NEW_SIGNUP 제한·LEGACY_EXEMPT 이용·정지/탈퇴·소유권/역할**을 유지해야 한다. DOB 필수 계약과 FE도 함께 맞아야 한다. 이전 develop `0d0234e...`/`ef8f0f7...` 런타임에는 이 후보의 신규 gate가 없으므로 자동 복귀 대상이 아니다. 검증된 복구 이미지가 없으면 담당자는 승인된 유지보수/트래픽 통제 상태에서 수정 이미지가 준비될 때까지 기다린다. 장애를 이유로 gate를 해제하지 않는다.
- 메일 발송 긴급 중지는 기존 `EDUPILOT_MAIL_ENABLED=false` 경계지만 이미 SENDING인 호출을 취소하거나 신규 사용자의 확인을 완료하지 않는다. 구 worker가 V54 outbox를 회수하지 않는 문제와 미완료/UNKNOWN 작업을 별도로 기록한다. 복구 과정에서도 유효 암호화 키를 보존한다.
- 물리 삭제 비활성·미정 목적 기한을 유지한다. 일반 30일은 확정된 범위이며 복구나 더 짧은 실행 정책으로 이미 기록한 `retainUntil`을 단축하지 않는다. 논리 삭제된 자료에 접근을 다시 열어 복구됐다고 표시하지 않는다.
- 실제 DB restore는 [운영 복구 런북](runbook-db-restore.md)의 별도 승인 작업이다. 오래된 백업과 독립 보관한 최신 [삭제 원장](deletion-journal.md)의 무결성/epoch를 검증하고 재적용 후 트래픽을 연다. 이메일/guardian/cohort 상태를 백업보다 최신이라고 추정하지 않는다. 원장 영구 반출 도구·파일 복구·실운영 리허설은 아직 완료 증거가 없다.

## 6. 결과 기록

이슈 #479/통합 PR에는 승인 범위, upstream/후보/FE/실행 중 이미지 SHA·digest, 같은 SHA CI, 로컬 검사와 실제 운영 검사를 구분한 결과, migration·백업 무결성, 키 유지 여부, 합성 역할별 접근 결과, 미완료/실패/미실행 및 복구 담당자를 남긴다. 비밀·실학생·실메일 본문은 포함하지 않는다. 실DEV NOTE04 fixture는 별도 계획·승인 작업이며 로컬 103개 dry-run이 실제 DEV 쓰기 승인이 되지 않는다.
