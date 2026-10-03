-- Old metadata-only queued rows cannot be replayed: never invent a payload or claim success.
UPDATE email_deliveries SET status = 'FAILED', error_summary = 'LEGACY_PAYLOAD_UNAVAILABLE'
WHERE status = 'QUEUED';

CREATE TABLE email_outbox (
    delivery_id BIGINT NOT NULL,
    encrypted_payload LONGBLOB NULL,
    status VARCHAR(20) NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80) NULL,
    PRIMARY KEY (delivery_id),
    INDEX idx_email_outbox_due (status, next_attempt_at, delivery_id),
    INDEX idx_email_outbox_lease (status, lease_until),
    CONSTRAINT fk_email_outbox_delivery FOREIGN KEY (delivery_id)
        REFERENCES email_deliveries(id) ON DELETE CASCADE,
    CONSTRAINT chk_email_outbox_status CHECK (status IN
        ('READY','CLAIMED','SENDING','RETRY','SENT','FAILED','UNKNOWN')),
    CONSTRAINT chk_email_outbox_attempts CHECK (attempt_count >= 0)
);
