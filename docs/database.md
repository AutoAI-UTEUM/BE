# 데이터베이스 설계

| 항목 | 내용 |
| --- | --- |
| 상태 | 논리 설계 초안 |
| 마지막 갱신 | 2026-08-02 |
| DB | MySQL |
| Migration | Flyway (DEC-003 Accepted) |

실제 컬럼 타입, 길이, 외래키 삭제 정책은 첫 migration 작성 전에 확정합니다. 아래 이름은 snake_case 기준 초안입니다.

## 1. 테이블 목록

| 테이블 | 핵심 컬럼 | 주요 제약/인덱스 |
| --- | --- | --- |
| `users` | id, email, password_hash, name, affiliation, avatar_key, learning_email_opt_in, 약관 버전·동의 시각, notification preferences, ai_answer_style, role, status, timestamps | `UK(email)`, `IDX(status)`, role·ai_answer_style CHECK |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at, created_at | `FK(user_id)`, `UK(token_hash)`, `IDX(user_id)` |
| `learning_materials` | id, owner_id, title, storage_key, page_count, processing_status, status, timestamps | `FK(owner_id)`, `UK(storage_key)`, `IDX(owner_id,status)`, 상태·page_count CHECK |
| `material_pages` | id, material_id, page_number, text_content, created_at | `FK(material_id)`, `UK(material_id,page_number)`, `CHECK(page_number >= 1)` |
| `classrooms` | id, instructor_id, name, start_date, end_date, color, description(nullable), status, invite_code, timestamps | `FK(instructor_id)`, `UK(invite_code)`, `IDX(instructor_id,status,created_at)`, 날짜·색상·상태 CHECK |
| `classroom_members` | id, classroom_id, user_id, joined_at, timestamps | `FK(classroom_id)`, `FK(user_id)`, `UK(classroom_id,user_id)`, `IDX(user_id,classroom_id)` |
| `classroom_join_requests` | id, classroom_id, user_id, status, requested_at, processed_at(nullable), timestamps | `FK(classroom_id)`, `FK(user_id)`, `UK(classroom_id,user_id)`, 요청 목록 인덱스, 상태 CHECK |
| `classroom_weeks` | id, classroom_id, week_number, title, release_at(nullable), timestamps | `FK(classroom_id)`, `UK(classroom_id,week_number)`, `CHECK(week_number >= 1)` |
| `classroom_week_materials` | id, week_id, material_id, added_at, timestamps | `FK(week_id)`, `FK(material_id)`, `UK(week_id,material_id)`, `IDX(material_id)` |
| `classroom_notices` | id, classroom_id, title, content, published_at, timestamps | `FK(classroom_id)`, `IDX(classroom_id,published_at,id)` |
| `learning_sessions` | id, user_id, material_id, current_page, page_status, status, conversation_summary, last_ui_actions_json, active_quiz_id, pending_diagnosis_id, active_turn_request_id, active_turn_started_at, version, timestamps | `FK(user_id)`, `FK(material_id)`, `IDX(user_id,status,updated_at)` |
| `session_page_records` | id, session_id, page_number, explained_at, timestamps | `FK(session_id)`, `UK(session_id,page_number)`, `CHECK(page_number >= 1)` |
| `chat_messages` | id, session_id, sender_type, message_type, content, page_number, request_id, status, created_at | `FK(session_id)`, `UK(session_id,request_id)`, `IDX(session_id,created_at,id)` |
| `notes` | id, user_id, material_id, session_id(nullable), page_number(nullable), source_message_id(nullable), content, timestamps | `FK(user_id)`, `FK(material_id)`, `FK(session_id)`, `FK(source_message_id)`, `IDX(user_id)`, `IDX(material_id,created_at,id)` |
| `feedbacks` | id, user_id, category, message, page_url(nullable), client_version(nullable), created_at | `FK(user_id)`, `IDX(user_id)`, category CHECK |
| `qa_threads` | id, session_id, page_number, status, timestamps | `FK(session_id)`, `IDX(session_id,status)` |
| `qa_messages` | id, qa_thread_id, chat_message_id, sender_type, content, created_at | `FK(qa_thread_id)`, `FK(chat_message_id)`, `IDX(qa_thread_id,created_at,id)` |
| `quizzes` | id, session_id, page_number, title, coverage_start_page, coverage_end_page, quiz_type, public_question_json, private_answer_json, schema_version, created_at | `FK(session_id)`, `IDX(session_id,created_at)` |
| `quiz_submissions` | id, quiz_id, user_id, attempt_no(MVP는 1 고정 — DEC-009), request_id, submitted_answer_json, score, max_score, passed, grading_result_json, created_at | `FK(quiz_id)`, `FK(user_id)`, `UK(quiz_id,user_id,attempt_no)`, `UK(quiz_id,user_id,request_id)` |
| `quiz_assessments` | id, session_id, quiz_submission_id, assessment_json, created_at | `FK(session_id)`, `FK(quiz_submission_id)`, `UK(quiz_submission_id)`, `IDX(session_id,created_at)` |
| `diagnoses` | id, session_id, quiz_submission_id, diagnostic_prompt, user_answer, diagnosis_result_json, status, timestamps | `FK(session_id)`, `FK(quiz_submission_id)`, `UK(quiz_submission_id)`, `IDX(session_id,status)` |
| `repair_results` | id, diagnosis_id, session_id, repair_content, repair_result_json, created_at | `FK(diagnosis_id)`, `FK(session_id)`, `UK(diagnosis_id)` |
| `learner_memories` | id, user_id, material_id, strengths_json, weaknesses_json, misconceptions_json, explanation_preferences_json, preferred_quiz_types_json, target_difficulty, next_coaching_goals_json, memory_digest, version, updated_at | `FK(user_id)`, `FK(material_id)`, `UK(user_id,material_id)` |
| `learner_memory_candidates` | id, user_id, material_id, candidate_type, content, confidence, evidence_refs_json, status, schema_version, timestamps | `FK(user_id)`, `FK(material_id)`, `IDX(user_id,material_id,status)` — DEC-012 Accepted |

- `learner_memory_candidates`는 임시 메모리 후보의 영속 저장소입니다. FastAPI는 무상태이므로 turn 응답의 후보를 Spring이 이 테이블에 `CANDIDATE`로 저장하고, 다음 턴 스냅샷의 `memory.temporaryCandidates`로 전달합니다. 스냅샷은 `evidence_refs_json.sessionId`가 현재 세션과 일치하는 후보만 최신순 최대 10개 포함합니다. `confidence`는 `DECIMAL(3,2)`의 0~1 값이며, 선택 후보 전체의 중복 없는 독립 근거가 2개 이상이고 모든 후보의 confidence가 0.70 이상일 때만 승격합니다. 승격되면 후보를 삭제하지 않고 `status`를 `PROMOTED`로 전환하며, `evidence_refs_json` + 상태 전이 기록이 MVP의 감사 이력 역할을 합니다(DEC-012 Accepted — 별도 이력 테이블은 이후 개선안).
- `quiz_assessments`는 삭제 없이 전량 보존합니다(DEC-011 Accepted). "평가 큐"는 물리 큐가 아니라 조회 윈도우입니다 — turn 스냅샷용은 세션 스코프 최근 5개(`IDX(session_id, created_at)`), 메모리 승격 판단용은 `quiz_submissions` 조인으로 user×material 교차 세션 최근 20개를 사용합니다.
- `learning_materials.processing_status`는 `PROCESSING`, `READY`, `FAILED` 3값을 사용하고 `status`는 `ACTIVE`, `DELETED`를 사용합니다. `page_count`는 처리 전·실패 시 `NULL`, READY일 때 1 이상입니다.
- `learning_sessions.conversation_summary`는 내부 AI 턴 스냅샷 전용이며 외부 세션 상세 응답에는 노출하지 않습니다. `last_ui_actions_json`, `active_quiz_id`, `pending_diagnosis_id`는 재진입 UI 복원용입니다. `active_quiz_id`와 `pending_diagnosis_id`에는 FK를 추가하지 않습니다. 세션이 하위 퀴즈·진단보다 먼저 생성되는 순환 참조 부담을 피하고 Spring이 생성·제출·진단 소유권과 상태를 검증합니다.
- `session_page_records`는 성공한 `EXPLAIN_CURRENT_PAGE` 턴이 `pageStatus=EXPLAINED`로 확정된 페이지를 기록합니다. 같은 페이지를 재설명하면 행을 추가하지 않고 `explained_at`을 갱신합니다. 진도율은 **설명 완료된 고유 페이지 수 ÷ 자료 `page_count` × 100을 정수 반올림**하며, 자료별 진도는 사용자×자료 범위의 ACTIVE·COMPLETED 세션 합집합으로 계산합니다. 이력이 없는 기존 세션은 `current_page`로 추정하지 않고 0으로 집계합니다.
- 강의실 진도율은 학습자가 볼 수 있는 `PUBLISHED` 주차에 연결된 고유 READY 자료를 대상으로 계산합니다. 분자는 사용자×자료 ACTIVE·COMPLETED 세션 합집합의 고유 `(material_id, page_number)` 설명 완료 수, 분모는 고유 자료의 `page_count` 합이며 정수 반올림합니다. 같은 자료가 여러 주차·강의실에 연결돼도 해당 사용자의 자료 학습 이력은 공유하고, 강의실 내 중복 자료는 한 번만 계산합니다. 이력 또는 유효한 분모가 없으면 0입니다.
- `learning_sessions`에는 `classroom_id`를 추가하지 않습니다. 강의실에서 시작한 통합학습도 기존 사용자×자료 ACTIVE 세션 재사용 규칙을 적용합니다.
- `classroom_weeks.status`와 `classrooms.week_count/current_week`는 저장하지 않습니다. 주차 상태는 `release_at`과 현재 UTC 시각, 주차 수·현재 주차는 강의실 날짜와 `Asia/Seoul`의 오늘로 파생합니다.
- `classrooms.color`는 `BLUE | GREEN | PURPLE | ORANGE | RED | GRAY`, `status`는 `ACTIVE | COMPLETED`입니다. 완료는 날짜가 아니라 명시적 상태 전환으로만 발생합니다.
- 강의실 컬럼 타입은 `name VARCHAR(100)`, `start_date/end_date DATE`, `color VARCHAR(20)`, `description VARCHAR(255) NULL`, `status VARCHAR(20)`, `invite_code VARCHAR(16)`입니다. 주차는 `week_number INT`, `title VARCHAR(100)`, `release_at DATETIME(6) NULL`, 공지는 `title VARCHAR(200)`, `content TEXT`, `published_at DATETIME(6)`을 사용합니다. 참여·요청·연결 시각도 `DATETIME(6)` UTC입니다.
- 강의실 관련 테이블은 모두 `BIGINT AUTO_INCREMENT` PK와 `created_at`, `updated_at`을 사용합니다. FK에는 자동 cascade를 두지 않고 주차·연결·공지 삭제 순서를 서비스 트랜잭션에서 명시적으로 처리합니다.
- `classroom_join_requests`는 사용자×강의실당 한 행입니다. `REJECTED` 재요청은 같은 행을 `PENDING`으로 갱신하고 `requested_at`을 새로 기록하며 `processed_at=NULL`로 되돌립니다.
- 강의실 자료 업로드 시 `learning_materials` 행과 `classroom_week_materials` 연결은 한 DB 트랜잭션으로 저장합니다. 파일 storage는 DB 트랜잭션에 참여하지 않으므로 DB 실패 시 저장 파일을 보상 삭제합니다.
- `notes`는 사용자와 자료에 귀속하며 세션·페이지·원본 채팅 메시지는 nullable 참조입니다. 목록은 사용자×ACTIVE 자료 범위로 조회하므로 자료가 논리 삭제되면 노트 행은 보존하되 API 목록에서는 제외합니다. 최신순은 `(created_at DESC, id DESC)`로 고정합니다.
- `feedbacks`는 인증 사용자를 작성자로 기록하고 `BUG | FEATURE_REQUEST | GENERAL` category와 최대 2,000자의 message를 저장합니다. 운영자 조회 API 없이 DB에서 직접 확인합니다.
- `quiz_submissions.score`와 `max_score`는 AI 부분점수를 보존하기 위해 `DECIMAL(10,2)`를 사용합니다. API 응답도 소수 둘째 자리까지 포함할 수 있습니다.
- refresh token 원문은 저장하지 않고 SHA-256 해시만 `refresh_tokens.token_hash`에 저장합니다. 회전·로그아웃·탈퇴 시 `revoked_at`을 기록합니다.
- `users.role`의 기본값은 `LEARNER`입니다. 공개 가입은 애플리케이션 계층에서 `LEARNER | INSTRUCTOR`만 허용하며 `ADMIN`은 예약 역할입니다.
- 계정 환경설정은 필드가 3개이고 사용자와 1:1이므로 별도 테이블 대신 `users` 컬럼으로 저장합니다. 기존 계정에는 `new_material_notification=true`, `study_reminder=true`, `ai_answer_style=NORMAL`을 적용합니다. `avatar_key`는 URL 대신 storage 상대 키를 저장하며 실제 파일은 `avatars/` 하위에 둡니다.

## 2. 컬럼 원칙

- 기본 키는 **BIGINT AUTO_INCREMENT**를 사용하고 외부 식별자도 동일 값을 노출합니다(DEC-007 Accepted — 외부 공개 확장 시 public ID 컬럼 추가로 개선).
- `created_at`, `updated_at`은 UTC 기준으로 저장합니다.
- 논리 삭제가 필요한 테이블은 `status` 또는 `deleted_at` 중 하나의 일관된 방식을 선택합니다.
- 비밀번호 컬럼명은 원문 `password` 대신 `password_hash`를 사용합니다.
- 파일 URL 자체보다 저장소 독립적인 `storage_key` 저장을 우선 검토합니다.
- `JSON` 컬럼은 AI 스키마 원본 보존에 사용하되 자주 조회·필터링할 필드는 정규 컬럼으로 분리합니다.
- JSON 데이터에는 `schemaVersion`을 포함하여 향후 변환 가능성을 확보합니다.

## 3. 핵심 제약조건

- `material_pages.page_number >= 1`
- `learning_materials.page_count IS NULL OR page_count >= 1`
- `learning_sessions.current_page >= 1`이며 애플리케이션에서 자료 `page_count` 이하인지 검증
- `session_page_records.page_number >= 1`
- `notes.page_number IS NULL OR page_number >= 1`
- `feedbacks.category IN (BUG, FEATURE_REQUEST, GENERAL)`
- `classrooms.end_date >= classrooms.start_date`
- `classrooms.color IN (BLUE, GREEN, PURPLE, ORANGE, RED, GRAY)`
- `classrooms.status IN (ACTIVE, COMPLETED)`
- `classroom_join_requests.status IN (PENDING, APPROVED, REJECTED)`
- `classroom_weeks.week_number >= 1`; 애플리케이션에서 계산된 `week_count` 이하인지 검증
- `classrooms.end_date` 축소 후 기존 최대 주차가 새 `week_count`를 넘으면 변경 거부
- 퀴즈 범위는 `1 <= coverage_start_page <= coverage_end_page <= page_count`
- `quiz_submissions.score >= 0`
- `quiz_submissions.max_score > 0`
- `quiz_submissions.score <= quiz_submissions.max_score`
- Diagnosis는 실패/보강 대상 제출에만 생성
- LearnerMemory는 `(user_id, material_id)`당 하나의 현재 스냅샷

MySQL CHECK 제약 지원 버전을 확인하고 DB 제약과 애플리케이션 검증을 함께 사용합니다.

## 4. 정답 정보 보호

- 퀴즈 공개 문제와 비공개 정답/루브릭은 필드를 분리합니다.
- Repository 엔티티를 API 응답으로 직접 직렬화하지 않습니다.
- FE DTO에는 `private_answer_json`을 매핑하지 않습니다.
- 관리자/디버그 API에서도 정답 노출 권한과 운영 비활성화를 별도 검토합니다.

## 5. 동시성·멱등성

- `learning_sessions.version`을 낙관적 잠금에 사용하고, `active_turn_request_id`·`active_turn_started_at` 조건부 갱신으로 세션당 동시 턴을 하나로 제한합니다. 5분이 지난 claim은 stale로 간주해 재획득할 수 있습니다.
- AI 턴은 사용자 `chat_messages.request_id`(`UK(session_id,request_id)`)로 클라이언트 `requestId`를 저장해 중복 처리를 방지하고 동일 요청은 `TURN_ALREADY_PROCESSED`로 거부합니다. AI 메시지의 `request_id`는 `NULL`이며 MySQL UNIQUE가 복수 `NULL`을 허용하는 성질을 사용합니다. 퀴즈 제출은 `quiz_submissions.request_id`(`UK(quiz_id,user_id,request_id)`)로 중복을 방지합니다.
- LearnerMemory 승격은 낙관적 잠금으로 덮어쓰기를 방지합니다.
- AI 호출 중 DB 트랜잭션을 오래 유지하지 않습니다. 호출 전 스냅샷과 호출 후 조건부 반영 패턴을 사용합니다.
- 자료 삭제와 추출 결과 반영은 `learning_materials` 행을 잠그고 상태를 재검증하여 삭제된 자료가 READY로 되살아나지 않게 합니다.
- 참여 요청 승인·거절은 요청 행을 잠그고 `PENDING`인지 재검증합니다. 승인은 요청 상태 변경과 `classroom_members` 생성을 한 트랜잭션으로 처리합니다.
- 초대 코드는 대문자·숫자 `XXXX-XXXX` 형식으로 생성하고 입력은 trim·대문자 정규화합니다. `UK(invite_code)` 충돌 시 새 코드를 생성해 제한된 횟수만큼 재시도합니다.
- 자료 연결·해제와 주차 삭제는 강의실·주차 소유권 및 상태를 잠금 범위에서 재검증합니다. 주차 삭제는 연결 행만 제거하고 자료 자체는 유지합니다.

## 6. 인덱스 초안

- 최근 세션: `learning_sessions(user_id, status, updated_at DESC)`
- 페이지 설명 이력: `session_page_records(session_id, page_number)` UNIQUE 인덱스
- 채팅 페이지네이션: `chat_messages(session_id, created_at, id)`
- 학습 노트: `notes(user_id)`, `notes(material_id, created_at, id)`
- 피드백 작성자: `feedbacks(user_id)`
- 활성 QA 스레드: `qa_threads(session_id, status)`
- 최근 퀴즈: `quizzes(session_id, created_at)`
- 최근 평가 큐: `quiz_assessments(session_id, created_at)`
- 대기 진단: `diagnoses(session_id, status)`
- 강사 강의실 목록: `classrooms(instructor_id, status, created_at, id)`
- 학습자 강의실 목록: `classroom_members(user_id, classroom_id)`
- 강의실 참여 요청: `classroom_join_requests(classroom_id, status, requested_at, id)`
- 내 참여 요청: `classroom_join_requests(user_id, status, requested_at, id)`
- 주차 자료 역조회: `classroom_week_materials(material_id)`
- 강의실 공지: `classroom_notices(classroom_id, published_at, id)`

실제 쿼리와 실행 계획을 확인하기 전 인덱스를 과도하게 추가하지 않습니다.

## 7. Migration 및 seed 원칙

- `V7__turn_integration.sql`은 `qa_threads`, `qa_messages`를 생성하고 `chat_messages.message_type`에 `SYSTEM`을 추가합니다.
- `V8__account_roles.sql`은 기존 `USER` 값을 `LEARNER`로 백필하고 role 기본값과 CHECK 제약을 `LEARNER | INSTRUCTOR | ADMIN`으로 교체합니다.
- `V9__session_page_records.sql`은 페이지 설명 완료 이력과 `(session_id, page_number)` 유일성을 추가합니다.
- `V10__account_profile.sql`은 가입 부가정보·아바타 키·약관 동의와 사용자 환경설정 컬럼을 추가합니다. nullable 프로필·약관 필드와 기본값이 있는 설정 컬럼으로 기존 계정 로그인을 유지합니다.
- `V11__learning_notes.sql`은 자료 귀속 학습 노트와 nullable 세션·페이지·채팅 메시지 참조, 사용자·자료 조회 인덱스를 추가합니다.
- `V12__feedbacks.sql`은 인증 사용자 귀속 피드백, category CHECK, 작성자 인덱스를 추가합니다.
- `V13__classroom_core.sql`은 강의실, 승인 멤버, 참여 요청과 초대 코드·상태·기간 제약을 추가합니다.
- `V14__classroom_weeks_materials.sql`은 강의실 주차와 주차별 자료 연결, 공개 시각·중복 연결 제약을 추가합니다.
- Epic10 강의실 migration은 구현 착수 시 최신 `origin/develop`의 다음 번호부터 코어(`classrooms`·멤버·참여 요청), 주차·자료, 공지 순서로 새 파일 3개를 추가합니다. 병렬 migration이 먼저 병합되면 rebase 후 번호를 조정하며 기존 migration은 수정하지 않습니다.
- QA 메시지는 원본 `chat_messages`와 1:1로 연결하며 `qa_messages.chat_message_id`에 UNIQUE를 둡니다.
- 활성 QA thread 조회는 `qa_threads(session_id, status)`, 문맥 복원은 `qa_messages(qa_thread_id, created_at, id)` 인덱스를 사용합니다.
- 운영 스키마 변경은 수동 DDL이 아니라 migration 파일로만 수행합니다.
- 이미 적용된 migration은 수정하지 않고 새 migration을 추가합니다.
- 파괴적 변경은 데이터 백필, 호환 배포, 롤백 계획을 함께 작성합니다.
- 로컬 seed에는 가짜 사용자와 저작권 문제가 없는 샘플 자료만 사용합니다.
- 운영 비밀값, 실제 사용자 데이터, 실제 Grok 응답 로그를 seed에 포함하지 않습니다.

## 8. 구현 전 결정 항목

확정됨: migration 도구(Flyway — DEC-003), PK 전략(BIGINT — DEC-007), PDF 저장소·처리 상태 enum(DEC-005·016), 페이지 진행 모델(단일 pageStatus — DEC-008), 퀴즈 재제출(1회 — DEC-009), 평가 큐 = 전량 보존 + 조회 윈도우(세션 5 / 승격용 교차 세션 20 — DEC-011), 메모리 승격 기준·감사 이력(독립 근거 2회 + candidates 보존 — DEC-012), 강의실 최소셋·자료 접근·진도·색상·시간 정책(DEC-030).

남은 항목:

- 보존 레코드·storage 파일의 물리 삭제·아카이빙 배치 정책 (DEC-028·DEC-011의 "이후 개선안" — 운영 전환 전 확정)
- LearnerMemory 항목별 변경 이력 테이블 (DEC-012 이후 개선안 — 필요 시)

