ALTER TABLE exam_answers
    ADD COLUMN manual_score DECIMAL(10, 2) NULL;

ALTER TABLE exam_answers
    ADD COLUMN adjusted_by BIGINT NULL;

ALTER TABLE exam_answers
    ADD COLUMN adjusted_at DATETIME(6) NULL;

ALTER TABLE exam_answers
    ADD CONSTRAINT chk_exam_answers_manual_score CHECK (
        manual_score IS NULL OR (manual_score >= 0 AND manual_score <= max_score)
    );
