ALTER TABLE learning_sessions
    ADD COLUMN last_summarized_message_id BIGINT NULL
        AFTER conversation_summary;
