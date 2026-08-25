ALTER TABLE exam_submissions
    ADD COLUMN grading_lease_token VARCHAR(36) NULL AFTER normalized_score,
    ADD COLUMN grading_lease_until DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000' AFTER grading_lease_token,
    ADD INDEX idx_exam_submissions_status_lease (status, grading_lease_until),
    ADD INDEX idx_exam_submissions_status_submitted (status, submitted_at);
