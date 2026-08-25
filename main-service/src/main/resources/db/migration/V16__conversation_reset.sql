ALTER TABLE learning_sessions
    ADD COLUMN conversation_reset_at DATETIME(6) NULL
        AFTER active_turn_started_at,
    ADD COLUMN conversation_reset_count INT NOT NULL DEFAULT 0
        AFTER conversation_reset_at,
    ADD CONSTRAINT chk_learning_sessions_conversation_reset_count
        CHECK (conversation_reset_count >= 0);
