ALTER TABLE chat_messages
    DROP CHECK chk_chat_messages_message_type,
    ADD CONSTRAINT chk_chat_messages_message_type
        CHECK (message_type IN (
            'TEXT',
            'EXPLANATION',
            'QA',
            'DIAGNOSIS',
            'REPAIR',
            'SYSTEM'
        ));

CREATE TABLE qa_threads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_qa_threads_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT chk_qa_threads_page_number CHECK (page_number >= 1),
    CONSTRAINT chk_qa_threads_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    INDEX idx_qa_threads_session_status (session_id, status)
);

CREATE TABLE qa_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    qa_thread_id BIGINT NOT NULL,
    chat_message_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_qa_messages_thread
        FOREIGN KEY (qa_thread_id) REFERENCES qa_threads (id),
    CONSTRAINT fk_qa_messages_chat
        FOREIGN KEY (chat_message_id) REFERENCES chat_messages (id),
    CONSTRAINT uk_qa_messages_chat UNIQUE (chat_message_id),
    CONSTRAINT chk_qa_messages_sender_type
        CHECK (sender_type IN ('USER', 'AI')),
    INDEX idx_qa_messages_thread_created_id
        (qa_thread_id, created_at, id)
);
