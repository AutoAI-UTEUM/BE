package io.edupilot.note;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LearningNoteMigrationContractTest {

	@Test
	void v11CreatesMaterialScopedNotesWithReferencesAndIndexes() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V11__learning_notes.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("CREATE TABLE notes")
			.contains("user_id BIGINT NOT NULL")
			.contains("material_id BIGINT NOT NULL")
			.contains("session_id BIGINT NULL")
			.contains("page_number INT NULL")
			.contains("source_message_id BIGINT NULL")
			.contains("content TEXT NOT NULL")
			.contains("FOREIGN KEY (user_id) REFERENCES users (id)")
			.contains("FOREIGN KEY (material_id) REFERENCES learning_materials (id)")
			.contains("FOREIGN KEY (session_id) REFERENCES learning_sessions (id)")
			.contains("FOREIGN KEY (source_message_id) REFERENCES chat_messages (id)")
			.contains("INDEX idx_notes_user_id (user_id)")
			.contains("INDEX idx_notes_material_created_id (material_id, created_at, id)");
	}
}
