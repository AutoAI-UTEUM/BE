package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SessionPageRecordMigrationContractTest {

	@Test
	void v9ContainsPageExplanationHistoryContract() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V9__session_page_records.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(
				input.readAllBytes(),
				StandardCharsets.UTF_8
			);
		}

		assertThat(migration)
			.contains("CREATE TABLE session_page_records")
			.contains("session_id BIGINT NOT NULL")
			.contains("page_number INT NOT NULL")
			.contains("explained_at DATETIME(6) NOT NULL")
			.contains("created_at DATETIME(6) NOT NULL")
			.contains("updated_at DATETIME(6) NOT NULL")
			.contains("fk_session_page_records_session")
			.contains("FOREIGN KEY (session_id)")
			.contains("uk_session_page_records_session_page")
			.contains("UNIQUE (session_id, page_number)")
			.contains("CHECK (page_number >= 1)");
	}
}
