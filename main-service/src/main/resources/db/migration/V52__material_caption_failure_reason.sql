ALTER TABLE learning_materials
    ADD COLUMN caption_failure_reason VARCHAR(40) NULL AFTER captions_completed_at,
    ADD CONSTRAINT chk_learning_materials_caption_failure_reason
        CHECK (
            caption_failure_reason IS NULL
            OR caption_failure_reason IN (
                'INVALID_PAGE_DIMENSIONS',
                'RENDER_LIMIT_EXCEEDED'
            )
        );
