-- Preserve existing notes (including edited duplicates); deduplication applies to new writes.
ALTER TABLE notes
    ADD COLUMN dedup_source_message_id BIGINT NULL;
ALTER TABLE notes
    ADD CONSTRAINT uk_notes_user_dedup_source UNIQUE (user_id, dedup_source_message_id);
ALTER TABLE notes
    ADD CONSTRAINT chk_notes_dedup_source
        CHECK (dedup_source_message_id IS NULL OR
            (source_message_id IS NOT NULL AND dedup_source_message_id = source_message_id));

CREATE INDEX idx_notes_user_source_id ON notes (user_id, source_message_id, id);
