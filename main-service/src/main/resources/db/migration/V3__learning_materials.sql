CREATE TABLE learning_materials (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    page_count INT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_materials_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_learning_materials_owner
        FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT chk_learning_materials_processing_status
        CHECK (processing_status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT chk_learning_materials_status
        CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_learning_materials_page_count
        CHECK (page_count IS NULL OR page_count >= 1),
    INDEX idx_learning_materials_owner_status (owner_id, status)
);

CREATE TABLE material_pages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    text_content MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_material_pages_material_page UNIQUE (material_id, page_number),
    CONSTRAINT fk_material_pages_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT chk_material_pages_page_number CHECK (page_number >= 1)
);
