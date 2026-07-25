# 데이터베이스 설계

| 항목 | 내용 |
| --- | --- |
| 상태 | 논리 설계 초안 |
| 마지막 갱신 | 2026-07-21 |
| DB | MySQL |
| Migration | Flyway (DEC-003 Accepted) |

실제 컬럼 타입, 길이, 외래키 삭제 정책은 첫 migration 작성 전에 확정합니다. 아래 이름은 snake_case 기준 초안입니다.

## 1. 테이블 목록

| 테이블 | 핵심 컬럼 | 주요 제약/인덱스 |
| --- | --- | --- |
| `users` | id, email, password_hash, name, role, status, timestamps | `UK(email)`, `IDX(status)` |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked_at, created_at | `FK(user_id)`, `UK(token_hash)`, `IDX(user_id)` |
| `learning_materials` | id, owner_id, title, storage_key, page_count, processing_status, status, timestamps | `FK(owner_id)`, `IDX(owner_id,status)` |
| `material_pages` | id, material_id, page_number, text_content, created_at | `FK(material_id)`, `UK(material_id,page_number)` |
| `learning_sessions` | id, user_id, material_id, current_page, page_status, status, conversation_summary, timestamps | `FK(user_id)`, `FK(material_id)`, `IDX(user_id,status,updated_at)` |
| `chat_messages` | id, session_id, sender_type, message_type, content, page_number, request_id, status, created_at | `FK(session_id)`, `UK(session_id,request_id)`, `IDX(session_id,created_at,id)` |
| `qa_threads` | id, session_id, page_number, status, timestamps | `FK(session_id)`, `IDX(session_id,status)` |
| `qa_messages` | id, qa_thread_id, chat_message_id, sender_type, content, created_at | `FK(qa_thread_id)`, `FK(chat_message_id)`, `IDX(qa_thread_id,created_at,id)` |
| `quizzes` | id, session_id, page_number, title, coverage_start_page, coverage_end_page, quiz_type, public_question_json, private_answer_json, schema_version, created_at | `FK(session_id)`, `IDX(session_id,created_at)` |
| `quiz_submissions` | id, quiz_id, user_id, attempt_no(MVP는 1 고정 — DEC-009), request_id, submitted_answer_json, score, max_score, passed, grading_result_json, created_at | `FK(quiz_id)`, `FK(user_id)`, `UK(quiz_id,user_id,attempt_no)`, `UK(quiz_id,user_id,request_id)` |
| `quiz_assessments` | id, session_id, quiz_submission_id, assessment_json, created_at | `FK(session_id)`, `FK(quiz_submission_id)`, `UK(quiz_submission_id)`, `IDX(session_id,created_at)` |
| `diagnoses` | id, session_id, quiz_submission_id, diagnostic_prompt, user_answer, diagnosis_result_json, status, timestamps | `FK(session_id)`, `FK(quiz_submission_id)`, `UK(quiz_submission_id)`, `IDX(session_id,status)` |
| `repair_results` | id, diagnosis_id, session_id, repair_content, repair_result_json, created_at | `FK(diagnosis_id)`, `FK(session_id)`, `UK(diagnosis_id)` |
| `learner_memories` | id, user_id, material_id, strengths_json, weaknesses_json, misconceptions_json, explanation_preferences_json, preferred_quiz_types_json, target_difficulty, next_coaching_goals_json, memory_digest, version, updated_at | `FK(user_id)`, `FK(material_id)`, `UK(user_id,material_id)` |
| `learner_memory_candidates` | id, user_id, material_id, candidate_type, content, confidence, evidence_refs_json, status, schema_version, timestamps | `FK(user_id)`, `FK(material_id)`, `IDX(user_id,material_id,status)` — DEC-012 Accepted |

- `learner_memory_candidates`는 임시 메모리 후보의 영속 저장소입니다. FastAPI는 무상태이므로 turn 응답의 후보를 Spring이 이 테이블에 저장하고, 다음 턴 스냅샷의 `memory.temporaryCandidates`로 전달합니다. 승격되면 후보를 삭제하지 않고 `status`를 `PROMOTED`/`ARCHIVED`로 전환하며, `evidence_refs_json` + 상태 전이 기록이 MVP의 감사 이력 역할을 합니다(DEC-012 Accepted — 별도 이력 테이블은 이후 개선안).
- `quiz_assessments`는 삭제 없이 전량 보존합니다(DEC-011 Accepted). "평가 큐"는 물리 큐가 아니라 조회 윈도우입니다 — turn 스냅샷용은 세션 스코프 최근 5개(`IDX(session_id, created_at)`), 메모리 승격 판단용은 `quiz_submissions` 조인으로 user×material 교차 세션 최근 20개를 사용합니다.
- `learning_materials.processing_status`는 `PROCESSING`, `READY`, `FAILED` 최소 3값을 사용합니다.
- refresh token 원문은 저장하지 않고 SHA-256 해시만 `refresh_tokens.token_hash`에 저장합니다. 회전·로그아웃·탈퇴 시 `revoked_at`을 기록합니다.

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
- `learning_sessions.current_page >= 1`이며 애플리케이션에서 자료 `page_count` 이하인지 검증
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

- `learning_sessions` 또는 별도 턴 레코드에 낙관적 잠금용 `version` 도입을 검토합니다.
- AI 턴은 `chat_messages.request_id`(`UK(session_id,request_id)`), 퀴즈 제출은 `quiz_submissions.request_id`(`UK(quiz_id,user_id,request_id)`)로 클라이언트 `requestId`를 저장해 중복 처리를 방지합니다. 동일 `requestId` 재전송은 `TURN_ALREADY_PROCESSED` 또는 기존 결과 반환으로 처리합니다.
- LearnerMemory 승격은 낙관적 잠금으로 덮어쓰기를 방지합니다.
- AI 호출 중 DB 트랜잭션을 오래 유지하지 않습니다. 호출 전 스냅샷과 호출 후 조건부 반영 패턴을 사용합니다.

## 6. 인덱스 초안

- 최근 세션: `learning_sessions(user_id, status, updated_at DESC)`
- 채팅 페이지네이션: `chat_messages(session_id, created_at, id)`
- 활성 QA 스레드: `qa_threads(session_id, status)`
- 최근 퀴즈: `quizzes(session_id, created_at)`
- 최근 평가 큐: `quiz_assessments(session_id, created_at)`
- 대기 진단: `diagnoses(session_id, status)`

실제 쿼리와 실행 계획을 확인하기 전 인덱스를 과도하게 추가하지 않습니다.

## 7. Migration 및 seed 원칙

- 운영 스키마 변경은 수동 DDL이 아니라 migration 파일로만 수행합니다.
- 이미 적용된 migration은 수정하지 않고 새 migration을 추가합니다.
- 파괴적 변경은 데이터 백필, 호환 배포, 롤백 계획을 함께 작성합니다.
- 로컬 seed에는 가짜 사용자와 저작권 문제가 없는 샘플 자료만 사용합니다.
- 운영 비밀값, 실제 사용자 데이터, 실제 Grok 응답 로그를 seed에 포함하지 않습니다.

## 8. 구현 전 결정 항목

확정됨: migration 도구(Flyway — DEC-003), PK 전략(BIGINT — DEC-007), PDF 저장소·처리 상태 enum(DEC-005·016), 페이지 진행 모델(단일 pageStatus — DEC-008), 퀴즈 재제출(1회 — DEC-009), 평가 큐 = 전량 보존 + 조회 윈도우(세션 5 / 승격용 교차 세션 20 — DEC-011), 메모리 승격 기준·감사 이력(독립 근거 2회 + candidates 보존 — DEC-012).

남은 항목:

- 보존 레코드·storage 파일의 물리 삭제·아카이빙 배치 정책 (DEC-028·DEC-011의 "이후 개선안" — 운영 전환 전 확정)
- LearnerMemory 항목별 변경 이력 테이블 (DEC-012 이후 개선안 — 필요 시)

