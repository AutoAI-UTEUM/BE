CREATE TABLE auth_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_sessions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_auth_sessions_expiration CHECK (
        last_activity_at <= idle_expires_at
        AND idle_expires_at <= absolute_expires_at
    ),
    INDEX idx_auth_sessions_user_active (user_id, revoked_at)
);

ALTER TABLE refresh_tokens
    ADD COLUMN session_id BIGINT NULL;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_session
        FOREIGN KEY (session_id) REFERENCES auth_sessions (id);

CREATE INDEX idx_refresh_tokens_session_active
    ON refresh_tokens (session_id, revoked_at);
