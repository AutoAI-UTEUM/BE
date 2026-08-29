package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ConversationSummaryMigrationContractTest {

	@Test
	void v35AddsNullableSummaryBoundaryWithoutIndex() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V35__conversation_summary_marker.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(
				input.readAllBytes(),
				StandardCharsets.UTF_8
			);
		}

		assertThat(migration)
			.contains("last_summarized_message_id BIGINT NULL")
			.doesNotContainIgnoringCase("INDEX");
	}
}
