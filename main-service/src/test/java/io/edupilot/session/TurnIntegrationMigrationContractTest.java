package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TurnIntegrationMigrationContractTest {

	@Test
	void v7ContainsQaPersistenceAndSystemMessageContract()
		throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V7__turn_integration.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(
				input.readAllBytes(),
				StandardCharsets.UTF_8
			);
		}

		assertThat(migration)
			.contains("CREATE TABLE qa_threads")
			.contains("CREATE TABLE qa_messages")
			.contains("idx_qa_threads_session_status")
			.contains("idx_qa_messages_thread_created_id")
			.contains("UNIQUE (chat_message_id)")
			.contains("'SYSTEM'");
	}
}
