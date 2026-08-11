ALTER TABLE exam_submissions
    ADD COLUMN grading_retry_count INT NOT NULL DEFAULT 0 AFTER grading_lease_until,
    ADD CONSTRAINT chk_exam_submissions_grading_retry_count
        CHECK (grading_retry_count >= 0);
