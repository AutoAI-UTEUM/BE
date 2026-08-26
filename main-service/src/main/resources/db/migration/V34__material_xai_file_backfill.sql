ALTER TABLE learning_materials
    ADD COLUMN xai_file_upload_attempted_at DATETIME(6) NULL AFTER xai_file_id,
    ADD INDEX idx_material_xai_file_backfill
        (status, processing_status, xai_file_id, xai_file_upload_attempted_at, id);
