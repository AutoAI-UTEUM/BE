CREATE TABLE material_overviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    content MEDIUMTEXT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_material_overviews_material UNIQUE (material_id),
    CONSTRAINT fk_material_overviews_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT chk_material_overviews_status
        CHECK (status IN ('PENDING', 'READY', 'FAILED'))
);
