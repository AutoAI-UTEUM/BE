package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ConversationResetMigrationContractTest {

	@Test
	void v16ContainsConversationBoundaryAndSequenceContract()
		throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V16__conversation_reset.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(
				input.readAllBytes(),
				StandardCharsets.UTF_8
			);
		}

		assertThat(migration)
			.contains("conversation_reset_at DATETIME(6) NULL")
			.contains("conversation_reset_count INT NOT NULL DEFAULT 0")
			.contains("CHECK (conversation_reset_count >= 0)");
	}
}
