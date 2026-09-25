ALTER TABLE users DROP CONSTRAINT chk_users_status;

ALTER TABLE users ADD COLUMN suspended_at DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN suspended_reason VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN suspended_by BIGINT NULL;

ALTER TABLE users
    ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));
