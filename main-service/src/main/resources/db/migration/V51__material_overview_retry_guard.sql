ALTER TABLE material_overviews
    ADD COLUMN generation_failure_count INT NOT NULL DEFAULT 0;

ALTER TABLE material_overviews
    ADD COLUMN generation_attempted_at DATETIME(6) NULL;

CREATE INDEX idx_material_overviews_retry
    ON material_overviews (status, generation_failure_count, generation_attempted_at);
