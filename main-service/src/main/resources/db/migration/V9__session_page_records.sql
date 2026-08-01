CREATE TABLE session_page_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    explained_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_session_page_records_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT uk_session_page_records_session_page
        UNIQUE (session_id, page_number),
    CONSTRAINT chk_session_page_records_page_number
        CHECK (page_number >= 1)
);
