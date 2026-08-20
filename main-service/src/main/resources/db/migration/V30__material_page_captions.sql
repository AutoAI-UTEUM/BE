ALTER TABLE material_pages
    ADD COLUMN caption TEXT NULL AFTER text_content;

ALTER TABLE learning_materials
    ADD COLUMN captions_completed_at DATETIME(6) NULL;
