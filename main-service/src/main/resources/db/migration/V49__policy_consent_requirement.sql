ALTER TABLE policy_documents
    ADD COLUMN requires_consent BOOLEAN NOT NULL DEFAULT FALSE AFTER summary;

-- The seeded 0.9 documents are legal-review placeholders. They must remain
-- readable for history, but must never block signup or application access.
UPDATE policy_documents
SET requires_consent = FALSE
WHERE version = '0.9';
