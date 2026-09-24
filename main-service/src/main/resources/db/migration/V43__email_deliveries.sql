CREATE TABLE email_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient VARCHAR(320) NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    provider_message_id VARCHAR(255) NULL,
    error_summary VARCHAR(500) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_email_deliveries_recipient_created (recipient, created_at),
    INDEX idx_email_deliveries_status_created (status, created_at),
    CONSTRAINT chk_email_deliveries_type CHECK (type IN (
        'PASSWORD_RESET', 'EMAIL_VERIFY', 'NOTIFICATION', 'TEST'
    )),
    CONSTRAINT chk_email_deliveries_status CHECK (status IN (
        'QUEUED', 'SENT', 'FAILED', 'RATE_LIMITED'
    ))
);
