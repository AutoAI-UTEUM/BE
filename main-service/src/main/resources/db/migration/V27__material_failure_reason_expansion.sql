ALTER TABLE learning_materials
    DROP CHECK chk_learning_materials_failure_reason,
    ADD CONSTRAINT chk_learning_materials_failure_reason
        CHECK (
            failure_reason IS NULL
            OR failure_reason IN (
                'EXTRACTION_FAILED',
                'PAGE_LIMIT_EXCEEDED',
                'SCHEDULING_FAILED',
                'UNSUPPORTED_FORMAT',
                'ENCRYPTED_PDF',
                'NO_TEXT_CONTENT',
                'FILE_TOO_LARGE'
            )
        );
