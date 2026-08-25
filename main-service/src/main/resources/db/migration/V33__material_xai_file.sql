ALTER TABLE learning_materials
    ADD COLUMN xai_file_id VARCHAR(255) NULL AFTER captions_completed_at;
