-- Tombstones deliberately outlive their referenced account/material rows; no cascading foreign keys.
CREATE TABLE deletion_journal_lock (id INT NOT NULL PRIMARY KEY, CONSTRAINT chk_deletion_lock CHECK (id = 1));
INSERT INTO deletion_journal_lock (id) VALUES (1);

CREATE TABLE deletion_intents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    key_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    source_material_key VARCHAR(255) NULL,
    source_user_id BIGINT NULL,
    account_created_at DATETIME(6) NULL,
    original_email_hash VARCHAR(64) NULL,
    requested_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    retain_until DATETIME(6) NULL,
    policy_version VARCHAR(100) NULL,
    attempts INT NOT NULL,
    generation BIGINT NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    failure_code VARCHAR(40) NULL,
    restore_epoch VARCHAR(100) NULL,
    CONSTRAINT uk_deletion_key_hash UNIQUE (key_hash),
    CONSTRAINT chk_deletion_kind CHECK (kind IN ('ACCOUNT','ORIGINAL_PDF','RENDERED_PAGES','EXTERNAL_AI','AVATAR')),
    CONSTRAINT chk_deletion_status CHECK (status IN ('RECORDED','POLICY_PENDING','READY','LEASED','RETRY','REFERENCE_PENDING','DONE','FAILED')),
    CONSTRAINT chk_deletion_attempts CHECK (attempts >= 0 AND generation >= 0),
    CONSTRAINT chk_deletion_lease CHECK ((status = 'LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT chk_deletion_binding CHECK (
        (kind = 'ACCOUNT' AND source_user_id IS NOT NULL AND source_user_id > 0 AND account_created_at IS NOT NULL
            AND original_email_hash IS NOT NULL AND source_material_key IS NULL AND status = 'RECORDED')
        OR (kind <> 'ACCOUNT' AND source_user_id IS NULL AND account_created_at IS NULL AND original_email_hash IS NULL
            AND ((kind IN ('ORIGINAL_PDF','RENDERED_PAGES') AND source_material_key IS NOT NULL AND resource_key = source_material_key)
                OR kind = 'EXTERNAL_AI' OR (kind = 'AVATAR' AND source_material_key IS NULL))))
);
CREATE INDEX idx_deletion_poll ON deletion_intents (status, next_attempt_at, id);
