package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QuizMigrationContractTest {

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
