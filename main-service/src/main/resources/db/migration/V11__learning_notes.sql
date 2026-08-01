CREATE TABLE notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    page_number INT NULL,
    source_message_id BIGINT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_notes_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notes_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT fk_notes_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT fk_notes_source_message
        FOREIGN KEY (source_message_id) REFERENCES chat_messages (id),
    CONSTRAINT chk_notes_page_number
        CHECK (page_number IS NULL OR page_number >= 1),
    INDEX idx_notes_user_id (user_id),
    INDEX idx_notes_material_created_id (material_id, created_at, id)
);
