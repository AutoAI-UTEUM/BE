ALTER TABLE learning_materials
    ADD COLUMN failure_reason VARCHAR(40) NULL AFTER processing_status,
    ADD COLUMN failure_trace_id VARCHAR(64) NULL AFTER failure_reason,
    ADD CONSTRAINT chk_learning_materials_failure_reason
        CHECK (
            failure_reason IS NULL
            OR failure_reason IN (
                'EXTRACTION_FAILED',
                'PAGE_LIMIT_EXCEEDED',
                'SCHEDULING_FAILED'
            )
        ),
    ADD CONSTRAINT chk_learning_materials_failure_metadata
        CHECK (
            processing_status = 'FAILED'
            OR (failure_reason IS NULL AND failure_trace_id IS NULL)
        );
