-- Operator-reviewed SELECT-only inventory. No recipient, subject, token, DOB or payload output.
-- Run the first inventory before the later blocks. Missing V54/V60/V61-V63 tables are UNKNOWN, not zero.
SELECT version, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('email_deliveries', 'email_outbox', 'email_send_reservations',
                     'birthdate_correction_requests', 'deletion_intents', 'guardian_team_requests',
                     'guardian_team_events', 'guardian_team_operations', 'guardian_team_mail_bindings')
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

-- Only after V61-V63 table presence is confirmed. No request/user IDs or guardian data output.
-- Counts are a point-in-time observation, not a freeze or a cleanup completion guarantee.
SELECT state, COUNT(*) AS requests
FROM guardian_team_requests GROUP BY state ORDER BY state;

SELECT COUNT(*) AS overdue_contacts
FROM guardian_team_requests
WHERE unconfirmed_erase_due_at <= UTC_TIMESTAMP(6)
  AND (guardian_name IS NOT NULL OR guardian_contact IS NOT NULL);

SELECT COUNT(*) AS overdue_evidence
FROM guardian_team_requests
WHERE evidence_erase_due_at <= UTC_TIMESTAMP(6) AND evidence_erased_at IS NULL;

SELECT COUNT(*) AS overdue_events
FROM guardian_team_events WHERE erase_due_at <= UTC_TIMESTAMP(6);

SELECT COUNT(*) AS overdue_operations
FROM guardian_team_operations WHERE erase_due_at <= UTC_TIMESTAMP(6);

SELECT COUNT(*) AS bound_notice_copies
FROM guardian_team_mail_bindings;

-- Separate ordinary file retention, account withdrawal and external AI pending work.
-- resource_key, key_hash, source_user_id and original_email_hash are deliberately excluded.
SELECT kind, status, COUNT(*) AS intents,
       COALESCE(SUM(CASE WHEN status IN ('READY','RETRY')
         AND next_attempt_at <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END), 0) AS due_intents
FROM deletion_intents GROUP BY kind, status ORDER BY kind, status;
