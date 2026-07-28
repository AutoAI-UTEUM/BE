CREATE TABLE learning_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    current_page INT NOT NULL DEFAULT 1,
    page_status VARCHAR(30) NOT NULL DEFAULT 'NOT_EXPLAINED',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    conversation_summary TEXT NULL,
    last_ui_actions_json JSON NULL,
    active_quiz_id BIGINT NULL,
    active_turn_request_id VARCHAR(255) NULL,
    active_turn_started_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_learning_sessions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_learning_sessions_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT chk_learning_sessions_current_page
        CHECK (current_page >= 1),
    CONSTRAINT chk_learning_sessions_page_status
        CHECK (page_status IN (
            'NOT_EXPLAINED',
            'EXPLAINING',
            'EXPLAINED',
            'QUIZ_READY',
            'DIAGNOSIS_PENDING',
            'REPAIR_COMPLETED'
        )),
    CONSTRAINT chk_learning_sessions_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'DELETED')),
    INDEX idx_learning_sessions_user_status_updated
        (user_id, status, updated_at)
);

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    page_number INT NOT NULL,
    request_id VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT uk_chat_messages_session_request
        UNIQUE (session_id, request_id),
    CONSTRAINT chk_chat_messages_sender_type
        CHECK (sender_type IN ('USER', 'AI')),
    CONSTRAINT chk_chat_messages_message_type
        CHECK (message_type IN ('TEXT', 'EXPLANATION', 'QA', 'DIAGNOSIS', 'REPAIR')),
    CONSTRAINT chk_chat_messages_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_chat_messages_page_number
        CHECK (page_number >= 1),
    INDEX idx_chat_messages_session_created_id
        (session_id, created_at, id)
);
