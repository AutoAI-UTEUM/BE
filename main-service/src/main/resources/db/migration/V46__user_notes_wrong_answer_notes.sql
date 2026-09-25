CREATE TABLE user_notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    material_id BIGINT NULL,
    page_number INT NULL,
    title VARCHAR(200) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    client_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_user_notes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_notes_material FOREIGN KEY (material_id)
        REFERENCES learning_materials (id) ON DELETE SET NULL,
    CONSTRAINT uk_user_notes_user_client UNIQUE (user_id, client_id)
);

CREATE INDEX idx_user_notes_user_updated_at ON user_notes (user_id, updated_at);
CREATE INDEX idx_user_notes_user_material ON user_notes (user_id, material_id);

CREATE TABLE wrong_answer_notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    quiz_result_ref VARCHAR(255) NOT NULL,
    question_snapshot JSON NOT NULL,
    memo TEXT NULL,
    client_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wrong_answer_notes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_wrong_answer_notes_user_client UNIQUE (user_id, client_id),
    CONSTRAINT uk_wrong_answer_notes_user_result UNIQUE (user_id, quiz_result_ref)
);

CREATE INDEX idx_wrong_answer_notes_user_updated_at
    ON wrong_answer_notes (user_id, updated_at);
