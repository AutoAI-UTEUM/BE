package io.edupilot.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FeedbackMigrationContractTest {

	@Test
	void v12CreatesAuthenticatedFeedbackStorageWithCategoryConstraint() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V12__feedbacks.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("CREATE TABLE feedbacks")
			.contains("user_id BIGINT NOT NULL")
			.contains("category VARCHAR(30) NOT NULL")
			.contains("message TEXT NOT NULL")
			.contains("page_url TEXT NULL")
			.contains("client_version TEXT NULL")
			.contains("created_at DATETIME(6) NOT NULL")
			.contains("FOREIGN KEY (user_id) REFERENCES users (id)")
			.contains("CHECK (category IN ('BUG', 'FEATURE_REQUEST', 'GENERAL'))")
			.contains("INDEX idx_feedbacks_user_id (user_id)");
	}
}
