CREATE TABLE feedbacks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    page_url TEXT NULL,
    client_version TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_feedbacks_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_feedbacks_category
        CHECK (category IN ('BUG', 'FEATURE_REQUEST', 'GENERAL')),
    INDEX idx_feedbacks_user_id (user_id)
);
