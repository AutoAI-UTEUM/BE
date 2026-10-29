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
    INDEX idx_email_outbox_expiry (status, expires_at, delivery_id),
    CONSTRAINT fk_email_outbox_delivery FOREIGN KEY (delivery_id)
        REFERENCES email_deliveries(id) ON DELETE CASCADE,
    CONSTRAINT chk_email_outbox_status CHECK (status IN
        ('READY','CLAIMED','SENDING','RETRY','SENT','FAILED','UNKNOWN')),
    CONSTRAINT chk_email_outbox_attempts CHECK (attempt_count >= 0)
);

-- This singleton serializes actual dispatch quota reservations across worker instances.
CREATE TABLE email_quota_lock (
    id INT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_email_quota_singleton CHECK (id = 1)
);
INSERT INTO email_quota_lock(id) VALUES (1);

CREATE TABLE email_send_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    claim_token VARCHAR(36) NOT NULL,
    reserved_at DATETIME(6) NOT NULL,
    units INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_email_reservation_claim UNIQUE (delivery_id, claim_token),
    INDEX idx_email_reservations_time (reserved_at),
    INDEX idx_email_reservations_delivery_time (delivery_id, reserved_at),
    CONSTRAINT fk_email_reservation_delivery FOREIGN KEY (delivery_id)
        REFERENCES email_deliveries(id) ON DELETE CASCADE,
    CONSTRAINT chk_email_reservation_units CHECK (units > 0)
);
-- Legacy rows have no attempt timestamp. Use known sent_at, or a conservative
-- migration-time budget reservation; this is not evidence of a new send.
INSERT INTO email_send_reservations(delivery_id, claim_token, reserved_at, units)
SELECT id, CONCAT('legacy-', id), COALESCE(sent_at, CURRENT_TIMESTAMP(6)),
       CASE WHEN attempt_count > 0 THEN attempt_count ELSE 1 END
FROM email_deliveries WHERE status = 'SENT' OR attempt_count > 0;
