package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QuizMigrationContractTest {

	@Test
	void v66MigratesExistingQuizRowsWithEmptyDraftAndZeroVersion() throws Exception {
		try (var connection = java.sql.DriverManager.getConnection(
			"jdbc:h2:mem:quiz-draft-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
			try (var statement = connection.createStatement()) {
				statement.execute("CREATE TABLE quizzes (id BIGINT PRIMARY KEY, title VARCHAR(255))");
				statement.execute("INSERT INTO quizzes VALUES (1, 'existing quiz')");
			}
			org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
				new org.springframework.core.io.ClassPathResource("db/migration/V66__learning_quiz_drafts.sql"));
			try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT * FROM quizzes")) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString("title")).isEqualTo("existing quiz");
				assertThat(row.getInt("draft_version")).isZero();
				assertThat(row.getString("draft_answers")).isNull();
				assertThat(row.getTimestamp("draft_saved_at")).isNull();
			}
		}
	}

	@Test
	void v66AddsDraftColumnsWithoutChangingExistingQuizData() throws Exception {
		try (var input = getClass().getResourceAsStream("/db/migration/V66__learning_quiz_drafts.sql")) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql).contains("ALTER TABLE quizzes", "draft_answers JSON NULL",
				"draft_version INT NOT NULL DEFAULT 0", "draft_saved_at DATETIME(6) NULL")
				.doesNotContain("DROP ", "DELETE ");
		}
	}

	@Test
	void v5ContainsQuizConstraintsAndIdempotencyKeys() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V5__quizzes.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("CREATE TABLE quizzes")
			.contains("CREATE TABLE quiz_submissions")
			.contains("DECIMAL(10, 2)")
			.contains("uk_quiz_submissions_attempt")
			.contains("UNIQUE (quiz_id, user_id, attempt_no)")
			.contains("uk_quiz_submissions_request")
			.contains("UNIQUE (quiz_id, user_id, request_id)")
			.contains("CHECK (score >= 0)")
			.contains("CHECK (max_score > 0)")
			.contains("CHECK (score <= max_score)");
	}
}
