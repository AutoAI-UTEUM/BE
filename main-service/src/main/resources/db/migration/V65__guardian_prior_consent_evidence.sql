-- Add evidence provenance only; never approve or backfill any account.
ALTER TABLE guardian_team_requests
    ADD COLUMN prior_consent_method VARCHAR(24) NULL;
ALTER TABLE guardian_team_requests
    ADD COLUMN evidence_reviewed_at DATETIME(6) NULL;
ALTER TABLE guardian_team_requests
    ADD COLUMN prior_consent_valid_until DATETIME(6) NULL;
ALTER TABLE guardian_team_requests
    ADD CONSTRAINT chk_guardian_team_prior_evidence CHECK (
        (prior_consent_method IS NULL AND evidence_reviewed_at IS NULL AND prior_consent_valid_until IS NULL
            AND (confirmation_method IS NULL OR confirmation_method <> 'PRIOR_RECORD'))
        OR (confirmation_method IS NOT NULL AND confirmation_method = 'PRIOR_RECORD' AND explicit_response_at IS NOT NULL
            AND evidence_reviewed_at IS NOT NULL AND prior_consent_valid_until IS NOT NULL
            AND prior_consent_method IS NOT NULL AND prior_consent_method IN ('EMAIL_REPLY', 'PHONE', 'SIGNED_DOCUMENT', 'IN_PERSON')
            AND explicit_response_at < generation_started_at
            AND explicit_response_at <= evidence_reviewed_at
            AND prior_consent_valid_until > evidence_reviewed_at));
