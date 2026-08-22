ALTER TABLE users
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN google_sub VARCHAR(64) NULL,
    ADD CONSTRAINT uk_users_google_sub UNIQUE (google_sub);
