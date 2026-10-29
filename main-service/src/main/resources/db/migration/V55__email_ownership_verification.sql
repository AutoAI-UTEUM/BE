-- Historical accounts have no confirmation evidence. Never backfill VERIFIED.
ALTER TABLE users ADD COLUMN email_verification_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE users ADD COLUMN email_verified_at DATETIME(6) NULL;
ALTER TABLE users ADD CONSTRAINT chk_user_email_verification_state CHECK (email_verification_state IN ('UNKNOWN','PENDING','VERIFIED'));
ALTER TABLE users ADD CONSTRAINT chk_user_email_verified_evidence CHECK (
        (email_verification_state = 'VERIFIED' AND email_verified_at IS NOT NULL) OR
        (email_verification_state IN ('UNKNOWN','PENDING') AND email_verified_at IS NULL));
CREATE TABLE email_verification_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    email_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_email_verification_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_email_verification_user_created (user_id, created_at),
    INDEX idx_email_verification_expiry (expires_at)
);
