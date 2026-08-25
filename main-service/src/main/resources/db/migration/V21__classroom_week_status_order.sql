ALTER TABLE classroom_weeks
    ADD COLUMN status VARCHAR(20) NULL AFTER release_at,
    ADD COLUMN display_order INT NULL AFTER status;

UPDATE classroom_weeks
SET status = CASE
        WHEN release_at IS NULL OR release_at <= UTC_TIMESTAMP(6) THEN 'PUBLISHED'
        ELSE 'SCHEDULED'
    END,
    display_order = week_number;

ALTER TABLE classroom_weeks
    MODIFY COLUMN status VARCHAR(20) NOT NULL,
    MODIFY COLUMN display_order INT NOT NULL,
    ADD CONSTRAINT chk_classroom_weeks_status
        CHECK (status IN ('PRIVATE', 'SCHEDULED', 'PUBLISHED', 'BREAK')),
    ADD CONSTRAINT chk_classroom_weeks_display_order
        CHECK (display_order >= 1),
    ADD INDEX idx_classroom_weeks_classroom_display_order
        (classroom_id, display_order);
