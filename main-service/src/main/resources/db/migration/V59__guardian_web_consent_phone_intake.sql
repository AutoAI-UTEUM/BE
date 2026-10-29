-- Self-declared consent and phone-control evidence only; no guardian/age approval state.
CREATE TABLE guardian_web_requests (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    notice_version VARCHAR(100) NOT NULL,
    notice_digest VARCHAR(64) NOT NULL,
    state VARCHAR(30) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consented_at DATETIME(6) NULL,
    legal_guardian_declared BOOLEAN NOT NULL DEFAULT FALSE,
    phone_fingerprint VARCHAR(64) NULL,
    phone_expires_at DATETIME(6) NULL,
    provider_reference VARCHAR(100) NULL,
    attempt_token VARCHAR(36) NULL,
    code_attempts INT NOT NULL DEFAULT 0,
    phone_confirmed_at DATETIME(6) NULL,
    exception_reason VARCHAR(30) NULL,
    CONSTRAINT fk_guardian_web_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_guardian_web_token UNIQUE (token_hash),
    CONSTRAINT chk_guardian_web_state CHECK (state IN
        ('AWAITING_CONSENT','SENDING','PHONE_PENDING','VERIFYING','PHONE_CONFIRMED','REVIEW_REQUIRED','CANCELLED')),
    CONSTRAINT chk_guardian_web_reason CHECK (exception_reason IS NULL OR exception_reason IN
        ('PROVIDER_UNAVAILABLE','PROVIDER_REJECTED','PROVIDER_RESULT_UNKNOWN','CODE_MISMATCH','EXPIRED','DISPUTED')),
    CONSTRAINT chk_guardian_web_attempts CHECK (code_attempts BETWEEN 0 AND 10),
    CONSTRAINT chk_guardian_web_consent CHECK (consented_at IS NULL OR legal_guardian_declared = TRUE),
    CONSTRAINT chk_guardian_web_phone_proof CHECK
        (state <> 'PHONE_CONFIRMED' OR (consented_at IS NOT NULL AND phone_confirmed_at IS NOT NULL))
);
CREATE INDEX idx_guardian_web_user_issued ON guardian_web_requests(user_id, issued_at);
CREATE INDEX idx_guardian_web_recovery ON guardian_web_requests(state, phone_expires_at, expires_at);
