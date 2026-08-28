# 데이터베이스 설계

| 항목 | 내용 |
| --- | --- |
| 상태 | 논리 설계 초안 |
| 마지막 갱신 | 2026-08-04 |
| DB | MySQL |
| Migration | Flyway (DEC-003 Accepted) |

실제 컬럼 타입, 길이, 외래키 삭제 정책은 첫 migration 작성 전에 확정합니다. 아래 이름은 snake_case 기준 초안입니다.

## 1. 테이블 목록

| 테이블 | 핵심 컬럼 | 주요 제약/인덱스 |
| --- | --- | --- |
| `users` | id, email, password_hash, auth_provider, google_sub(nullable), name, affiliation, avatar_key, learning_email_opt_in, 약관 버전·동의 시각, notification preferences, ai_answer_style, role, status, timestamps | `UK(email)`, `UK(google_sub)`, `IDX(status)`, role·ai_answer_style CHECK |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at, created_at | `FK(user_id)`, `UK(token_hash)`, `IDX(user_id)` |
| `learning_materials` | id, owner_id, title, storage_key, page_count, processing_status, failure_reason(nullable), failure_trace_id(nullable), captions_completed_at(nullable), xai_file_id(nullable), xai_file_upload_attempted_at(nullable), status, timestamps | `FK(owner_id)`, `UK(storage_key)`, `IDX(owner_id,status)`, `IDX(status,processing_status,xai_file_id,xai_file_upload_attempted_at,id)`, 상태·실패 사유·page_count CHECK |
| `material_pages` | id, material_id, page_number, text_content, caption(nullable), created_at | `FK(material_id)`, `UK(material_id,page_number)`, `CHECK(page_number >= 1)` |
| `material_overviews` | id, material_id, content(nullable), outline_json(nullable), status, timestamps | `FK(material_id)`, `UK(material_id)`, status CHECK |
| `classrooms` | id, instructor_id, name, start_date, end_date, color, description(nullable), status, invite_code, timestamps | `FK(instructor_id)`, `UK(invite_code)`, `IDX(instructor_id,status,created_at)`, 날짜·색상·상태 CHECK |
| `classroom_members` | id, classroom_id, user_id, joined_at, timestamps | `FK(classroom_id)`, `FK(user_id)`, `UK(classroom_id,user_id)`, `IDX(user_id,classroom_id)` |
| `classroom_join_requests` | id, classroom_id, user_id, status, requested_at, processed_at(nullable), timestamps | `FK(classroom_id)`, `FK(user_id)`, `UK(classroom_id,user_id)`, 요청 목록 인덱스, 상태 CHECK |
| `classroom_weeks` | id, classroom_id, week_number, title, release_at(nullable), status, display_order, timestamps | `FK(classroom_id)`, `UK(classroom_id,week_number)`, `IDX(classroom_id,display_order)`, 주차 번호·상태·표시 순서 CHECK |
| `classroom_week_materials` | id, week_id, material_id, added_at, timestamps | `FK(week_id)`, `FK(material_id)`, `UK(week_id,material_id)`, `IDX(material_id)` |
| `classroom_notices` | id, classroom_id, week_number(nullable), title, content, published_at, publish_at(nullable), notification_sent_at(nullable), timestamps | `FK(classroom_id)`, `IDX(classroom_id,published_at,id)`, 주차 번호 CHECK |
| `classroom_resource` | id, classroom_id, type, title, week_number(nullable), file metadata(nullable), storage_path(nullable), url(nullable), timestamps | `FK(classroom_id)`, `IDX(classroom_id,week_number,created_at,id)`, 유형·주차·파일 크기·메타데이터 조합 CHECK |
| `notifications` | id, user_id, type, title, body, link_json, read_at(nullable), created_at | `FK(user_id)`, `IDX(user_id,created_at)`, type CHECK |
| `user_schedules` | id, user_id, title, starts_at, ends_at, has_time, timestamps | `FK(user_id)`, `IDX(user_id,starts_at)`, `CHECK(ends_at >= starts_at)` |
| `exams` | id, classroom_id, week_number(nullable), title, description(nullable), status, allow_retake, total_score, published_at(nullable), closed_at(nullable), timestamps | `FK(classroom_id)`, `IDX(classroom_id,status)`, 상태·총점 CHECK |
| `exam_questions` | id, exam_id, question_no, question_type, points, public_question_json, private_answer_json, schema_version, timestamps | `FK(exam_id)`, `UK(exam_id,question_no)`, 유형·점수 CHECK |
| `exam_submissions` | id, exam_id, user_id, attempt_no, request_id, status, submitted_at, graded_at(nullable), score(nullable), max_score, normalized_score(nullable), grading_lease_token(nullable), grading_lease_until, grading_retry_count, timestamps | `FK(exam_id)`, `FK(user_id)`, 시도·멱등 UK, 상태·점수·재시도 CHECK, 상태+lease·제출시각 인덱스 |
| `exam_answers` | id, submission_id, question_id, answer(nullable), score(nullable), max_score, verdict(nullable), feedback(nullable), timestamps | `FK(submission_id)`, `FK(question_id)`, `UK(submission_id,question_id)`, 점수·판정 CHECK |
| `report_criteria` | id, classroom_id, criterion_key, name, description(nullable), rubric_json, allowed_sources_json, min_evidence, weight, version, active, timestamps | `FK(classroom_id)`, `UK(classroom_id,criterion_key,version)`, `IDX(classroom_id,active)`, 최소 근거·weight·version CHECK |
| `report_generations` | id, classroom_id, student_id, requested_by, request_id, scope_type, week_number(nullable), scope_hash, snapshot_hash(nullable), criterion_catalog_json(nullable), policy_version, source_data_as_of(nullable), status, failure_code(nullable), model(nullable), prompt_version(nullable), generation lease, timestamps | 강의실·학생·요청자 FK, `UK(classroom_id,student_id,request_id)`, status+lease·학생별 상태 인덱스, 범위·주차·상태 CHECK |
| `student_reports` | id, generation_id, classroom_id, student_id, scope_key, version, previous_report_id(nullable), overall_score(nullable), overall_stage(nullable), summary(nullable), data_quality_json, model, prompt_version, timestamps | generation·강의실·학생·이전 리포트 FK, `UK(generation_id)`, `UK(classroom_id,student_id,scope_key,version)`, scope key·version·점수 CHECK |
| `report_criterion_results` | id, report_id, criterion_key, criterion_version, score(nullable), trend(nullable), status, narrative(nullable), evidence_ids_json, timestamps | `FK(report_id)`, `UK(report_id,criterion_key)`, 점수·trend·status·ASSESSED 점수 필수 CHECK |
| `report_evidence_snapshots` | id, generation_id, evidence_id, source_type, source_ref, occurred_at, public_label, minimal_fact_json, source_hash, timestamps | `FK(generation_id)`, `UK(generation_id,evidence_id)`, `IDX(generation_id,source_type)` |
| `learning_sessions` | id, user_id, material_id, current_page, page_status, status, conversation_summary, last_ui_actions_json, active_quiz_id, pending_diagnosis_id, active_turn_request_id, active_turn_started_at, conversation_reset_at, conversation_reset_count, version, timestamps | `FK(user_id)`, `FK(material_id)`, `IDX(user_id,status,updated_at)`, `CHECK(conversation_reset_count >= 0)` |
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
- `learning_materials.processing_status`는 `PROCESSING`, `READY`, `FAILED` 3값을 사용하고 `status`는 `ACTIVE`, `DELETED`를 사용합니다. `page_count`는 처리 전·실패 시 `NULL`, READY일 때 1 이상입니다. V23부터 신규 FAILED 전이는 `failure_reason`(`EXTRACTION_FAILED | PAGE_LIMIT_EXCEEDED | SCHEDULING_FAILED | UNSUPPORTED_FORMAT | ENCRYPTED_PDF | NO_TEXT_CONTENT | FILE_TOO_LARGE`)과 업로드 요청의 `failure_trace_id`를 함께 저장합니다. V27은 이 코드 목록을 7종으로 확장하며 기존 행 backfill은 수행하지 않습니다. 기존 FAILED 행은 복원할 수 없어 두 컬럼의 `NULL`을 유지하며, FAILED가 아닌 행은 두 컬럼이 `NULL`이어야 합니다.
- V30은 `material_pages.caption`과 `learning_materials.captions_completed_at`을 nullable로 추가하며 기존 데이터 backfill은 하지 않습니다. `captions_completed_at`은 캡션 전량 성공이 아니라 모든 청크의 생성 시도가 끝났음을 뜻합니다. 캡션은 AI 입력 조립 시점에만 `text_content`와 병합하며 추출 원문은 수정하지 않습니다. ACTIVE·READY이면서 완료 시각이 없는 자료는 오래된 순으로 백필 대상이 됩니다.
- V33은 `learning_materials.xai_file_id`를 nullable로 추가합니다. 추출 성공 응답의 non-blank xAI file ID만 내부 저장하며 외부 자료 응답에는 노출하지 않습니다. 자료 소프트 삭제와 계정 탈퇴 시 트랜잭션 커밋 후 내부 파일 삭제 API를 베스트에포트로 호출하고, 실패해도 자료 삭제 결과는 유지합니다.
- V34는 기존 ACTIVE·READY 자료의 bounded xAI Files 백필을 위해 nullable `xai_file_upload_attempted_at`과 후보 인덱스를 추가합니다. claim 시각을 먼저 커밋해 중복 worker와 hot loop를 막고 기본 6시간 뒤 재시도하며, 업로드 실패는 READY 상태를 변경하지 않습니다.
- V31은 기존 사용자를 `LOCAL`로 유지하는 `users.auth_provider`와 nullable `google_sub` 및 Google subject 유일 제약을 추가합니다.
- `material_overviews`는 자료당 최대 1행이며 `PENDING | READY | FAILED` 상태를 사용합니다. `content`와 V29의 `outline_json`은 nullable입니다. READY는 결정적으로 렌더한 Markdown과 AI 구조화 개요를 함께 보존하고, FAILED는 둘 다 null로 유지합니다. `outline_json`에는 nullable `sections[].description`과 `quizCheckpoints[{triggerPage,coverage}]`도 저장하며 구버전 JSON의 필드 부재를 허용합니다. 조회 응답에는 READY의 `content`만 노출하며 `outline_json`은 내부 저장용입니다. 행이 없는 자료는 API에서 PENDING으로 합성합니다. 자료 추출 완료 후 개요를 비동기로 생성하고, 기존 ACTIVE·READY 자료 중 개요 행이 없거나 READY 개요에 `quizCheckpoints`가 없는 자료는 기존 batch 크기 안에서 오래된 순으로 백필합니다. 개요 실패는 `learning_materials` 상태를 변경하지 않습니다.
- `learning_sessions.conversation_summary`는 내부 AI 턴 스냅샷 전용이며 외부 세션 상세 응답에는 노출하지 않습니다. `last_ui_actions_json`, `active_quiz_id`, `pending_diagnosis_id`는 재진입 UI 복원용입니다. `active_quiz_id`와 `pending_diagnosis_id`에는 FK를 추가하지 않습니다. 세션이 하위 퀴즈·진단보다 먼저 생성되는 순환 참조 부담을 피하고 Spring이 생성·제출·진단 소유권과 상태를 검증합니다.
- `learning_sessions.conversation_reset_at`은 AI 문맥 경계 시각이며 `conversation_reset_count`는 외부 `conversation-{n}` 표기의 순번입니다. 새 대화 이후 내부 턴 스냅샷은 마커보다 늦게 생성된 메시지만 `recentMessages`에 포함하고, 마커 이전 `qaThreadDigest`와 `latestRepair`를 null로 처리합니다. `pendingDiagnosis`, 임시 메모리 후보, 평가, 장기 메모리는 유지하며 메시지 조회 API는 마커와 무관하게 전체 이력을 반환합니다.
- `chat_messages.status`는 `PENDING | COMPLETED | FAILED`를 사용합니다. AI 호출·검증·저장 실패 시 선커밋된 사용자 메시지를 보상 트랜잭션으로 `FAILED` 처리하며, 실패 메시지는 조회 이력에는 보존하되 다음 턴의 `recentMessages`와 `qaThreadDigest`에서 제외합니다. 동일 request ID 재시도는 실패 행을 `COMPLETED`로 복귀시켜 재사용하므로 질문 행을 추가하지 않습니다.
- `session_page_records`는 성공한 `EXPLAIN_CURRENT_PAGE` 턴이 `pageStatus=EXPLAINED`로 확정된 페이지를 기록합니다. 같은 페이지를 재설명하면 행을 추가하지 않고 `explained_at`을 갱신합니다. 진도율은 **설명 완료된 고유 페이지 수 ÷ 자료 `page_count` × 100을 정수 반올림**하며, 자료별 진도는 사용자×자료 범위의 ACTIVE·COMPLETED 세션 합집합으로 계산합니다. 이력이 없는 기존 세션은 `current_page`로 추정하지 않고 0으로 집계합니다.
- 리포트 페이지 진도는 DEC-033에 따라 V9 `session_page_records`를 그대로 사용합니다. 동등한 설명 완료 근거가 이미 있으므로 `session_page_progress` 테이블은 새로 만들지 않습니다.
- 강의실 진도율은 학습자에게 보이는 주차(`PUBLISHED`, `BREAK`, 공개일이 지난 `SCHEDULED`)에 연결된 고유 READY 자료를 대상으로 계산합니다. 분자는 사용자×자료 ACTIVE·COMPLETED 세션 합집합의 고유 `(material_id, page_number)` 설명 완료 수, 분모는 고유 자료의 `page_count` 합이며 정수 반올림합니다. 같은 자료가 여러 주차·강의실에 연결돼도 해당 사용자의 자료 학습 이력은 공유하고, 강의실 내 중복 자료는 한 번만 계산합니다. 이력 또는 유효한 분모가 없으면 0입니다.
- `learning_sessions`에는 `classroom_id`를 추가하지 않습니다. 강의실에서 시작한 통합학습도 기존 사용자×자료 ACTIVE 세션 재사용 규칙을 적용합니다.
- `classroom_weeks.status`는 `PRIVATE | SCHEDULED | PUBLISHED | BREAK` 정본으로 저장합니다. `PRIVATE`는 미노출, `SCHEDULED`는 `release_at`이 있고 현재 UTC 시각이 공개일에 도달했을 때 노출, `PUBLISHED`와 `BREAK`는 항상 노출합니다. 상태 전이 스케줄러 없이 조회 시점에 판정하며 `week_number`는 재정렬로 변경하지 않습니다. `classrooms.week_count/current_week`는 저장하지 않고 강의실 날짜와 `Asia/Seoul`의 오늘로 파생합니다.
- `classrooms.color`는 `BLUE | GREEN | PURPLE | ORANGE | RED | GRAY`, `status`는 `ACTIVE | COMPLETED`입니다. 완료는 날짜가 아니라 명시적 상태 전환으로만 발생합니다.
- 강의실 컬럼 타입은 `name VARCHAR(100)`, `start_date/end_date DATE`, `color VARCHAR(20)`, `description VARCHAR(255) NULL`, `status VARCHAR(20)`, `invite_code VARCHAR(16)`입니다. 주차는 `week_number INT`, `title VARCHAR(100)`, `release_at DATETIME(6) NULL`, `status VARCHAR(20)`, `display_order INT`를 사용하고, 공지는 `week_number INT NULL`, `title VARCHAR(200)`, `content TEXT`, `published_at DATETIME(6)`, `publish_at DATETIME(6) NULL`을 사용합니다. 일반 자료는 `type FILE | LINK`, `title VARCHAR(200)`, `week_number INT NULL`, 원본 파일 메타데이터 또는 `url VARCHAR(2048)`을 유형별로 저장합니다. 공지·일반 자료의 `week_number` 상한은 애플리케이션에서 강의실 `weekCount`로 검증합니다. 참여·요청·연결 시각도 `DATETIME(6)` UTC입니다.
- 강의실 관련 테이블은 모두 `BIGINT AUTO_INCREMENT` PK와 `created_at`, `updated_at`을 사용합니다. FK에는 자동 cascade를 두지 않고 주차·연결·공지 삭제 순서를 서비스 트랜잭션에서 명시적으로 처리합니다.
- 별도 시험은 강의실에 귀속하고 `week_number`는 nullable 표시·집계 라벨로만 사용합니다. 값이 있으면 `1 <= week_number <= classroom.week_count`를 애플리케이션에서 검증하되 `classroom_weeks` 행의 존재를 요구하지 않습니다. `exams.status`는 `DRAFT | PUBLISHED | CLOSED`, `allow_retake` 기본값은 false입니다.
- DRAFT 시험은 문항 0개와 `total_score=0`을 허용하므로 DB 제약은 `total_score >= 0`입니다. 공개 시 애플리케이션이 문항 1개 이상과 `total_score > 0`을 검증하며, 문항 전체 교체 시 합계를 다시 계산합니다. 공개 이후 문항과 설정은 변경하지 않습니다.
- `exam_questions`는 `question_no`를 1부터 부여하고 외부 `questionId`를 `q{question_no}`로 파생합니다. 공개 JSON과 정답·모범 답안·rubric이 담긴 비공개 JSON을 분리하며 학생 DTO에는 비공개 JSON을 매핑하지 않습니다. SHORT/ESSAY rubric 키가 없거나 빈 배열이면 grade 호출 시 서버 기본 rubric을 주입합니다.
- `exam_submissions`는 모든 재응시를 보존합니다. `(exam_id,user_id,attempt_no)`와 `(exam_id,user_id,request_id)`를 각각 UNIQUE로 둡니다. 운영 조회·polling의 최신 제출은 `MAX(attempt_no)`, 성적·리포트 대표 제출은 `MAX(attempt_no WHERE status='GRADED')`로 파생합니다. `GRADING_FAILED` 뒤에도 이전 GRADED 시도가 있으면 이전 점수가 대표값이며, GRADED 시도가 없으면 집계에서 제외합니다. `max_score`는 제출 시점 총점 스냅샷이며 `normalized_score=ROUND(score/max_score*100,2)`는 완전한 채점 후 Spring이 계산합니다.
- 비동기 채점 lease는 `grading_lease_token VARCHAR(36) NULL`과 `grading_lease_until DATETIME(6) NOT NULL`을 사용합니다. lease 없음은 token null과 epoch 시각으로 표현합니다. claim은 `status=SUBMITTED AND grading_lease_until < now`, 결과 반영은 `status=SUBMITTED AND grading_lease_token=:token` 조건입니다. terminal 전환 시 lease를 초기화합니다.
- `grading_retry_count INT NOT NULL DEFAULT 0`은 30분 채점 창 소진 횟수이며 0 이상입니다. `SUBMITTED` 상태의 `updated_at`은 마지막 채점 시도 시작 시각으로만 갱신하고, 최초 제출·lease claim·컷오프 재큐잉·강사 재채점 외 경로에서 제출을 수정하지 않습니다. `updated_at <= now-30분`이면 active lease보다 우선해 첫 두 컷오프는 카운트를 1·2로 증가시켜 재큐잉하고, 세 번째 컷오프는 카운트 3과 `GRADING_FAILED`를 확정합니다. 강사 재채점은 저장 답안을 유지한 채 상태를 `SUBMITTED`, 카운트를 0으로 되돌립니다.
- AI 채점 전·실패 상태에서는 제출 `score`, `normalized_score`, `graded_at`과 해당 AI 답안의 `score`, `verdict`, `feedback`이 NULL입니다. 결정적 MCQ/OX 결과는 즉시 저장하고, 미응답은 `answer=NULL`, `score=0`, `verdict=WRONG`, `feedback=NULL`로 확정합니다. 따라서 답안 점수 제약은 `score IS NULL OR (score >= 0 AND score <= max_score)` 형태입니다.
- 시험과 문항 FK에는 자동 cascade를 두지 않습니다. DRAFT 물리 삭제와 문항 전체 교체는 하위 `exam_questions`를 서비스 트랜잭션에서 먼저 제거하며 PUBLISHED 이후에는 삭제·교체하지 않습니다.
- 기본 평가 기준 9종은 버전 상수를 포함한 코드 카탈로그로 관리합니다. `report_criteria`는 강의실 커스텀 기준 전용이며, 기본 기준을 DB seed로 넣지 않습니다. 기본 9종과 활성 커스텀 기준의 합계 20개 상한 및 정규화 이름 중복은 criterion CRUD 서비스가 검증합니다. 커스텀 기준 삭제는 최신 version ID로만 허용하고 같은 key의 전 version을 물리 삭제합니다. `report_criterion_results`는 기준 key·version을 독립 보존하며 `report_criteria` FK가 없고, 진행 중 generation은 시작 시 동결한 기준 snapshot을 사용합니다.
- `report_generations`는 비동기 생성 회수를 위해 시험 채점과 같은 token·epoch lease 표현을 사용합니다. 완료 generation당 리포트 1건과 학생·scope 체인별 리포트 version 중복은 UNIQUE로 막고, FAILED generation 승격 금지와 완료 버전 불변성은 서비스 불변식으로 검증합니다.
- `student_reports.scope_key`는 `FULL` 또는 `WEEK:{weekNumber}`입니다. FULL과 주차별 WEEK는 version·`previous_report_id`·trend 체인을 각각 독립적으로 유지합니다. V25 backfill은 generation의 scope를 사용하되 기존 `previous_report_id`와 trend는 역사적 값으로 보존합니다.
- `notifications`는 인앱 전용이며 `MATERIAL_UPLOADED | NOTICE_PUBLISHED | JOIN_REQUEST_RECEIVED | JOIN_REQUEST_PROCESSED` 네 유형과 FE 라우팅용 `link_json`을 저장합니다. 타인 알림은 소유권 조회에서 은닉하고 생성 후 30일 초과분은 배치 물리 삭제합니다. 예약 공지는 `classroom_notices.notification_sent_at`을 발송 표식으로 사용하고 수신자 bulk insert와 같은 트랜잭션에서 기록합니다.
- `report_criterion_results.trend`는 같은 scope 체인의 직전 점수 이력으로 Spring이 결정적으로 계산해 저장하며 AI 요청·응답에는 포함하지 않습니다. `report_questions`는 Phase 3에서 별도 migration으로 추가합니다.
- `classroom_join_requests`는 사용자×강의실당 한 행입니다. `REJECTED` 재요청은 같은 행을 `PENDING`으로 갱신하고 `requested_at`을 새로 기록하며 `processed_at=NULL`로 되돌립니다.
- 강의실 자료 업로드 시 `learning_materials` 행과 `classroom_week_materials` 연결은 한 DB 트랜잭션으로 저장합니다. 파일 storage는 DB 트랜잭션에 참여하지 않으므로 DB 실패 시 저장 파일을 보상 삭제합니다.
- `notes`는 사용자와 자료에 귀속하며 세션·페이지·원본 채팅 메시지는 nullable 참조입니다. 목록은 사용자×ACTIVE 자료 범위로 조회하므로 자료가 논리 삭제되면 노트 행은 보존하되 API 목록에서는 제외합니다. 최신순은 `(created_at DESC, id DESC)`로 고정합니다.
- `feedbacks`는 인증 사용자를 작성자로 기록하고 `BUG | FEATURE_REQUEST | GENERAL` category와 최대 2,000자의 message를 저장합니다. 운영자 조회 API 없이 DB에서 직접 확인합니다.
- `quiz_submissions.score`와 `max_score`는 AI 부분점수를 보존하기 위해 `DECIMAL(10,2)`를 사용합니다. API 응답도 소수 둘째 자리까지 포함할 수 있습니다.
- refresh token 원문은 저장하지 않고 SHA-256 해시만 `refresh_tokens.token_hash`에 저장합니다. 회전·로그아웃·탈퇴 시 `revoked_at`을 기록합니다.
- `users.role`의 기본값은 `LEARNER`입니다. 공개 가입은 애플리케이션 계층에서 `LEARNER | INSTRUCTOR`만 허용하며 `ADMIN`은 예약 역할입니다.
- `users.auth_provider`는 계정 최초 생성 경로인 `LOCAL | GOOGLE`을 저장합니다. 검증된 이메일과 일치하는 로컬 계정에 Google 로그인을 자동 연결할 때는 `auth_provider=LOCAL`을 유지하고 nullable `google_sub`만 기록합니다. Google 최초 가입은 `password_hash='!oauth:google'` sentinel을 저장해 비밀번호 로그인을 차단합니다. 탈퇴 시 `google_sub=NULL`로 해제해 같은 Google 계정의 재가입을 허용합니다.
- 계정 환경설정은 필드가 3개이고 사용자와 1:1이므로 별도 테이블 대신 `users` 컬럼으로 저장합니다. 기존 계정에는 `new_material_notification=true`, `study_reminder=true`, `ai_answer_style=NORMAL`을 적용합니다. `avatar_key`는 URL 대신 storage 상대 키를 저장하며 실제 파일은 `avatars/` 하위에 둡니다.

## 2. 컬럼 원칙

- 기본 키는 **BIGINT AUTO_INCREMENT**를 사용하고 외부 식별자도 동일 값을 노출합니다(DEC-007 Accepted — 외부 공개 확장 시 public ID 컬럼 추가로 개선).
- `created_at`, `updated_at`을 포함한 모든 시각은 UTC 기준으로 저장합니다. MySQL 커넥션 세션에는 `connectionTimeZone=UTC`와 `forceConnectionTimeZoneToSession=true`를 적용해 DB의 `CURRENT_TIMESTAMP`도 UTC로 평가합니다.
- Hibernate 타임스탬프 처리를 우회하는 벌크 JPQL·JDBC 쓰기는 UTC `Instant`를 명시적으로 전달하며, 정상 애플리케이션 쓰기에서 DB `CURRENT_TIMESTAMP`에만 의존하지 않습니다. JDBC URL은 `EDUPILOT_DB_URL` 환경변수로 주입되므로 URL의 시간대 옵션 유무와 관계없이 커넥션 프로퍼티를 적용합니다.
- 논리 삭제가 필요한 테이블은 `status` 또는 `deleted_at` 중 하나의 일관된 방식을 선택합니다.
- 비밀번호 컬럼명은 원문 `password` 대신 `password_hash`를 사용합니다.
- 파일 URL 자체보다 저장소 독립적인 `storage_key` 저장을 우선 검토합니다.
- `JSON` 컬럼은 AI 스키마 원본 보존에 사용하되 자주 조회·필터링할 필드는 정규 컬럼으로 분리합니다.
- JSON 데이터에는 `schemaVersion`을 포함하여 향후 변환 가능성을 확보합니다.

## 3. 핵심 제약조건

- `material_pages.page_number >= 1`
- `learning_materials.page_count IS NULL OR page_count >= 1`
- `learning_sessions.current_page >= 1`이며 애플리케이션에서 자료 `page_count` 이하인지 검증
- `learning_sessions.conversation_reset_count >= 0`
- `session_page_records.page_number >= 1`
- `notes.page_number IS NULL OR page_number >= 1`
- `feedbacks.category IN (BUG, FEATURE_REQUEST, GENERAL)`
- `classrooms.end_date >= classrooms.start_date`
- `classrooms.color IN (BLUE, GREEN, PURPLE, ORANGE, RED, GRAY)`
- `classrooms.status IN (ACTIVE, COMPLETED)`
- `classroom_join_requests.status IN (PENDING, APPROVED, REJECTED)`
- `classroom_weeks.week_number >= 1`; 애플리케이션에서 계산된 `week_count` 이하인지 검증
- `classrooms.end_date` 축소 후 기존 최대 주차가 새 `week_count`를 넘으면 변경 거부
- `exams.total_score >= 0`; 공개 시 애플리케이션에서 `total_score > 0` 검증
- `exam_questions.question_no >= 1`, `exam_questions.points > 0`
- `exam_submissions.attempt_no >= 1`, `exam_submissions.max_score > 0`
- `exam_submissions.score IS NULL OR (score >= 0 AND score <= max_score)`
- `exam_submissions.normalized_score IS NULL OR (normalized_score >= 0 AND normalized_score <= 100)`
- `exam_answers.score IS NULL OR (score >= 0 AND score <= max_score)`
- `report_criteria.min_evidence >= 1`, `report_criteria.weight > 0`, `report_criteria.version >= 1`
- `report_generations.scope_type IN (FULL, WEEK)`, `week_number IS NULL OR week_number >= 1`, `status IN (PENDING, PROCESSING, COMPLETED, FAILED)`
- `student_reports.version >= 1`, `overall_score IS NULL OR (overall_score >= 0 AND overall_score <= 100)`
- `report_criterion_results.score IS NULL OR (score >= 0 AND score <= 100)`
- `report_criterion_results.trend IS NULL OR trend IN (UP, FLAT, DOWN)`
- `report_criterion_results.status IN (ASSESSED, INSUFFICIENT_DATA)`이며 ASSESSED이면 score 필수
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
- AI 턴은 사용자 `chat_messages.request_id`(`UK(session_id,request_id)`)로 클라이언트 `requestId`를 저장해 중복 처리를 방지하고 동일 요청은 `TURN_ALREADY_PROCESSED`로 거부합니다. AI 메시지의 `request_id`는 `NULL`이며 MySQL UNIQUE가 복수 `NULL`을 허용하는 성질을 사용합니다. 퀴즈 제출은 `quiz_submissions.request_id`(`UK(quiz_id,user_id,request_id)`)로 중복을 방지하고, 동일 `(quiz_id,user_id,request_id)` 재전송 시 저장된 제출·채점 결과를 재구성합니다. 제출 행에 저장되지 않는 `uiActions`는 활성 진단이 있으면 진단 액션을, 그 외에는 세션의 영속 `last_ui_actions_json`을 사용해 누락 없이 복원합니다.
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
- 강의실 일반 자료: `classroom_resource(classroom_id, week_number, created_at, id)`
- 내 인앱 알림: `notifications(user_id, created_at)`
- 강의실 시험 목록: `exams(classroom_id, status, created_at, id)`
- 학생별 시험 시도: `exam_submissions(exam_id, user_id, attempt_no)` UNIQUE
- 시험 제출 멱등성: `exam_submissions(exam_id, user_id, request_id)` UNIQUE
- 시험 채점 회수: `exam_submissions(status, grading_lease_until)`
- 시험 제출 시각 조회: `exam_submissions(status, submitted_at)`
- 커스텀 리포트 기준: `report_criteria(classroom_id, active)`
- 리포트 생성 회수: `report_generations(status, generation_lease_until)`
- 학생별 리포트 생성 상태: `report_generations(classroom_id, student_id, status)`
- 생성 근거 유형: `report_evidence_snapshots(generation_id, source_type)`

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
- `V15__classroom_notices.sql`은 즉시 게시 강의실 공지와 강의실별 게시 시각 인덱스를 추가합니다.
- `V16__conversation_reset.sql`은 새 대화의 AI 문맥 경계 시각과 세션별 순번을 추가합니다.
- `V17__exam.sql`은 `exams`, `exam_questions`, `exam_submissions`, `exam_answers` 4개 테이블과 시험 계약 제약을 추가합니다.
- `V18__exam_grading_lease.sql`은 기존 V17 checksum을 변경하지 않고 시험 제출의 lease 컬럼 2개와 회수 인덱스 2개만 추가합니다.
- `V19__report.sql`은 커스텀 평가 기준, 비동기 생성, 학생 리포트 버전, 기준별 결과, 생성 시점 근거 snapshot의 5개 테이블과 generation lease를 추가합니다. 기본 평가 기준 9종은 seed하지 않으며 페이지 진도 테이블도 추가하지 않습니다.
- `V20__user_schedules.sql`은 사용자 귀속 개인 일정, 시작 시각 조회 인덱스와 `ends_at >= starts_at` 제약을 추가합니다. 모든 행이 개인 일정이므로 `kind` 컬럼은 두지 않습니다.
- `V21__classroom_week_status.sql`은 주차 상태·표시 순서 정본과 기존 주차 공개 상태 backfill을 추가합니다.
- `V22__classroom_notice_week_publish.sql`은 공지의 nullable 주차 번호와 예약 게시 시각을 추가합니다. 기존 행은 두 컬럼 모두 null로 유지해 전체 공지·즉시 게시 동작을 보존합니다.
- `V23__material_failure_metadata.sql`은 자료 처리 실패의 nullable 코드 사유와 업로드 요청 traceId를 추가합니다. 기존 FAILED 행은 복원할 수 없어 두 컬럼을 null로 유지합니다.
- `V24__exam_grading_retry.sql`은 시험 채점의 30분 창 재시도 횟수와 non-negative CHECK를 추가합니다. 기존 제출은 0으로 초기화합니다.
- `V25__report_scope_chain.sql`은 generation의 scope를 `student_reports.scope_key`로 backfill하고 학생별 전역 version 유니크를 학생·scope 체인별 version 유니크로 교체합니다. backfill할 수 없는 행은 임의 기본값 없이 NOT NULL 전환에서 배포를 중단합니다.
- `V26__in_app_notifications.sql`은 사용자 귀속 인앱 알림 테이블과 최신순 인덱스, 예약 공지 1회 생성 표식을 추가합니다. 기존 즉시 게시·이미 도래한 공지는 발송 완료로 backfill하고 미래 예약 공지만 스캔 대상으로 남깁니다.
- `V32__classroom_resources.sql`은 AI 추출 대상이 아닌 강의실 일반 파일·링크 자료와 유형별 메타데이터 제약, 주차별 최신순 조회 인덱스를 추가합니다. 파일은 `classroom-resources/` storage 하위에 UUID 키로 저장합니다.
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
