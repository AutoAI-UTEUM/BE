ALTER TABLE classroom_notices
    ADD COLUMN week_number INT NULL AFTER classroom_id,
    ADD COLUMN publish_at DATETIME(6) NULL AFTER published_at,
    ADD CONSTRAINT chk_classroom_notices_week_number
        CHECK (week_number IS NULL OR week_number >= 1);
