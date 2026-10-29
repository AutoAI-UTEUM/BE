-- No existing account is approved or assigned guardian consent by this migration.
ALTER TABLE users ADD COLUMN guardian_approved_until DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN guardian_ai_consent_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN guardian_consent_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN guardian_approval_policy_digest VARCHAR(64) NULL;
ALTER TABLE users DROP CHECK chk_user_age_verification_state;
ALTER TABLE users ADD CONSTRAINT chk_user_age_verification_state
    CHECK (age_verification_state IN ('UNKNOWN','MANUAL_PENDING','TEAM_APPROVED'));
ALTER TABLE users ADD CONSTRAINT chk_user_guardian_consent_epoch CHECK (guardian_consent_epoch >= 0);
ALTER TABLE users ADD CONSTRAINT chk_user_guardian_team_approval CHECK
    (age_verification_state <> 'TEAM_APPROVED'
        OR (guardian_approved_until IS NOT NULL AND guardian_approval_policy_digest IS NOT NULL));
