ALTER TABLE users
    ADD COLUMN affiliation VARCHAR(100) NULL AFTER name,
    ADD COLUMN avatar_key VARCHAR(255) NULL AFTER affiliation,
    ADD COLUMN learning_email_opt_in BOOLEAN NOT NULL DEFAULT FALSE AFTER avatar_key,
    ADD COLUMN terms_version VARCHAR(50) NULL AFTER learning_email_opt_in,
    ADD COLUMN privacy_version VARCHAR(50) NULL AFTER terms_version,
    ADD COLUMN consented_at DATETIME(6) NULL AFTER privacy_version,
    ADD COLUMN new_material_notification BOOLEAN NOT NULL DEFAULT TRUE AFTER consented_at,
    ADD COLUMN study_reminder BOOLEAN NOT NULL DEFAULT TRUE AFTER new_material_notification,
    ADD COLUMN ai_answer_style VARCHAR(20) NOT NULL DEFAULT 'NORMAL' AFTER study_reminder,
    ADD CONSTRAINT chk_users_ai_answer_style
        CHECK (ai_answer_style IN ('CONCISE', 'NORMAL', 'DETAILED'));
