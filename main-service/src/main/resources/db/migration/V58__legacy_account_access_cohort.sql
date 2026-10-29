-- Existing accounts retain access without inventing email, age or guardian evidence.
ALTER TABLE users ADD COLUMN access_cohort VARCHAR(24) NOT NULL DEFAULT 'LEGACY_EXEMPT';

-- Every account created after this migration, including SQL inserts, is a new signup.
ALTER TABLE users ALTER COLUMN access_cohort SET DEFAULT 'NEW_SIGNUP';
ALTER TABLE users ADD CONSTRAINT chk_user_access_cohort
    CHECK (access_cohort IN ('LEGACY_EXEMPT', 'NEW_SIGNUP'));
