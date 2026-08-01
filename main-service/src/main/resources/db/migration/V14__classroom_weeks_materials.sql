CREATE TABLE classroom_weeks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    week_number INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    release_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_weeks_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT uk_classroom_weeks_classroom_number
        UNIQUE (classroom_id, week_number),
    CONSTRAINT chk_classroom_weeks_number CHECK (week_number >= 1)
);

CREATE TABLE classroom_week_materials (
    id BIGINT NOT NULL AUTO_INCREMENT,
    week_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    added_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_week_materials_week
        FOREIGN KEY (week_id) REFERENCES classroom_weeks (id),
    CONSTRAINT fk_classroom_week_materials_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT uk_classroom_week_materials_week_material
        UNIQUE (week_id, material_id),
    INDEX idx_classroom_week_materials_material (material_id)
);
