-- PREPARATION ONLY. C did not connect to a server or execute these statements.
-- Existing read-only connection, reviewed DEV target and query budget are prerequisites.
-- Each numbered block is independent. Run inventory first; run later blocks only if
-- their tables/columns exist and the operator approves their scope/scan cost.
-- :observed_at_utc, :processing_cutoff_utc, :window_start_utc and :window_end_utc
-- are private prepared-statement parameters, not mysql-shell variables or shell text.
-- Do not substitute missing/denied reads with zero. No IDs, answers, traces or secrets leave the connection.

-- 1. Schema metadata only. Flyway signed integer checksum is NOT a SHA-256.
SELECT version, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT COUNT(*) AS failed_migrations
FROM flyway_schema_history WHERE success = 0;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('learning_materials', 'learning_sessions', 'session_page_records',
                     'quiz_submissions', 'exam_submissions', 'exam_answers',
                     'ai_usage_log', 'deletion_intents')
ORDER BY table_name;

SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'ai_usage_log'
        AND column_name IN ('cost_usd_ticks', 'request_id', 'input_tokens', 'output_tokens',
                            'reasoning_tokens', 'success', 'created_at'))
    OR (table_name = 'exam_submissions'
        AND column_name IN ('grading_lease_token', 'grading_lease_until'))
    OR (table_name = 'learning_materials'
        AND column_name IN ('processing_status', 'failure_reason', 'updated_at'))
    OR (table_name = 'deletion_intents'
        AND column_name IN ('kind', 'status', 'next_attempt_at')))
ORDER BY table_name, column_name;

-- 2. Worker observations; this never claims/retries/cleans a job.
-- Derive the cutoff from the reviewed effective recovery setting, not a guessed timeout.
SELECT COUNT(*) AS materials,
       COALESCE(SUM(processing_status = 'PROCESSING'), 0) AS processing_materials,
       COALESCE(SUM(processing_status = 'READY'), 0) AS ready_materials,
       COALESCE(SUM(processing_status = 'FAILED'), 0) AS failed_materials,
       COALESCE(SUM(status = 'ACTIVE' AND processing_status = 'PROCESSING'
                    AND updated_at <= :processing_cutoff_utc), 0) AS stale_processing_materials
FROM learning_materials;

SELECT COUNT(*) AS expired_grading_leases
FROM exam_submissions
WHERE status = 'SUBMITTED' AND grading_lease_token IS NOT NULL
  AND grading_lease_until <= :observed_at_utc;

SELECT COALESCE(SUM(status IN ('READY', 'RETRY')
                    AND next_attempt_at <= :observed_at_utc), 0) AS due_deletion_intents,
       COALESCE(SUM(status = 'FAILED'), 0) AS failed_deletion_intents,
       COALESCE(SUM(status = 'POLICY_PENDING'), 0) AS policy_pending_deletion_intents
FROM deletion_intents;

-- 3. Progress/score diagnostics only. These are NOT UI-vs-DB reconciliation receipts.
-- Internal grouping keys remain inside the subquery; output is a count only.
SELECT COUNT(*) AS invalid_page_record_rows
FROM session_page_records page_record
JOIN learning_sessions session_row ON session_row.id = page_record.session_id
JOIN learning_materials material ON material.id = session_row.material_id
WHERE page_record.page_number < 1
   OR (material.page_count IS NOT NULL AND page_record.page_number > material.page_count);

SELECT COUNT(*) AS duplicate_session_page_groups
FROM (SELECT session_id, page_number
      FROM session_page_records
      GROUP BY session_id, page_number HAVING COUNT(*) > 1) duplicate_groups;

SELECT COUNT(*) AS invalid_graded_score_rows
FROM exam_submissions
WHERE status = 'GRADED'
  AND (score IS NULL OR score < 0 OR score > max_score OR max_score <= 0
       OR normalized_score IS NULL OR normalized_score < 0 OR normalized_score > 100);

-- 4. Usage for one bounded UTC interval. Use the identical interval/feature scope
-- in an EXISTING provider-ledger receipt. No provider sync/API request is part of this plan.
-- 1 USD = 10,000,000,000 cost_usd_ticks. Keep sums as decimal strings.
-- COALESCE totals describe known values only; null-row counts preserve UNKNOWN separately.
SELECT COUNT(*) AS usage_rows,
       COALESCE(SUM(success = 1), 0) AS successful_rows,
       COALESCE(SUM(success = 0), 0) AS failed_rows,
       COALESCE(SUM(input_tokens IS NULL OR output_tokens IS NULL
                    OR reasoning_tokens IS NULL), 0) AS unknown_token_rows,
       CAST(COALESCE(SUM(input_tokens), 0) AS CHAR) AS input_tokens_known,
       CAST(COALESCE(SUM(output_tokens), 0) AS CHAR) AS output_tokens_known,
       CAST(COALESCE(SUM(reasoning_tokens), 0) AS CHAR) AS reasoning_tokens_known,
       COUNT(cost_usd_ticks) AS known_cost_rows,
       COUNT(*) - COUNT(cost_usd_ticks) AS unknown_cost_rows,
       CAST(COALESCE(SUM(cost_usd_ticks), 0) AS CHAR) AS app_known_cost_usd_ticks
FROM ai_usage_log
WHERE created_at >= :window_start_utc AND created_at < :window_end_utc;

SELECT COUNT(*) AS duplicate_usage_execution_groups
FROM (SELECT request_id
      FROM ai_usage_log
      WHERE created_at >= :window_start_utc AND created_at < :window_end_utc
        AND request_id IS NOT NULL
      GROUP BY request_id HAVING COUNT(*) > 1) duplicate_groups;

-- 5. Quota inventory without user identifiers or a new AI call.
-- The four prechecked feature classes are TURN, DOC_CHAT, QUIZ_ASSESSMENT, DIAGNOSIS.
-- Current AiQuotaService counts all successful AND failed rows since KST midnight;
-- ADMIN exemption/disabled quota and the upload/grading/report fan-out boundary are separate.
-- This global aggregate cannot establish each user's quota or a strict concurrent ceiling.
SELECT COALESCE(SUM(feature IN ('TURN', 'DOC_CHAT', 'QUIZ_ASSESSMENT', 'DIAGNOSIS')), 0)
         AS rows_from_prechecked_feature_classes,
       COALESCE(SUM(feature NOT IN ('TURN', 'DOC_CHAT', 'QUIZ_ASSESSMENT', 'DIAGNOSIS')), 0)
         AS rows_from_other_feature_classes
FROM ai_usage_log
WHERE created_at >= :window_start_utc AND created_at < :window_end_utc;
