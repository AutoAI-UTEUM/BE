-- Operator-reviewed SELECT-only inventory. No recipient, subject, token, DOB or payload output.
-- Run the first inventory before the later blocks. Missing V54/V60 tables are UNKNOWN, not zero.
SELECT version, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('email_deliveries', 'email_outbox', 'email_send_reservations',
                     'birthdate_correction_requests', 'deletion_intents')
ORDER BY table_name;

-- Only after V54 table presence is confirmed. This snapshot does not freeze workers/producers.
SELECT status, COUNT(*) AS deliveries, COALESCE(SUM(attempt_count), 0) AS attempts
FROM email_deliveries GROUP BY status ORDER BY status;

SELECT status, COUNT(*) AS jobs,
       SUM(CASE WHEN expires_at <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END) AS expired_jobs,
       SUM(CASE WHEN status IN ('READY', 'RETRY')
                 AND next_attempt_at <= UTC_TIMESTAMP(6)
                 AND expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END) AS due_jobs,
       SUM(CASE WHEN status IN ('CLAIMED', 'SENDING') THEN 1 ELSE 0 END) AS in_flight_jobs
FROM email_outbox GROUP BY status ORDER BY status;

SELECT COALESCE(SUM(units), 0) AS kst_today_reserved_units
FROM email_send_reservations
WHERE reserved_at >= TIMESTAMP(DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR)) - INTERVAL 9 HOUR;

-- Only after V60 table presence is confirmed. Values are counts, never the requested DOB.
SELECT COUNT(*) AS deleted_user_request_dob_leaks
FROM birthdate_correction_requests request
JOIN users account ON account.id = request.user_id
WHERE account.status = 'DELETED'
  AND (request.requested_date_of_birth IS NOT NULL OR request.state <> 'WITHDRAWN');
