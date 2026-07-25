package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SessionMigrationContractTest {

	@Test
	void v4ContainsRequiredIdempotencyAndPaginationIndexes() throws IOException {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V4__learning_sessions.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("idx_learning_sessions_user_status_updated")
			.contains("(user_id, status, updated_at)")
			.contains("uk_chat_messages_session_request")
			.contains("UNIQUE (session_id, request_id)")
			.contains("request_id VARCHAR(255) NULL")
			.contains("idx_chat_messages_session_created_id")
			.contains("(session_id, created_at, id)");
	}
}
