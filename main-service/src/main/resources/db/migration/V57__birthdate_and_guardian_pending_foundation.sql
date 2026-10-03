-- Capture new signup input; existing accounts remain UNKNOWN with no invented birth date or approval.
ALTER TABLE users ADD COLUMN date_of_birth DATE NULL,
    ADD COLUMN age_verification_state VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT chk_user_age_verification_state CHECK (age_verification_state IN ('UNKNOWN','MANUAL_PENDING'));

CREATE TABLE guardian_verification_requests (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_guardian_intake_user UNIQUE (user_id),
    CONSTRAINT fk_guardian_intake_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_guardian_intake_status CHECK (status IN ('PENDING','CANCELLED'))
);
