package io.edupilot.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class LearningSupportMigrationContractTest {

	@Test
	void v6ContainsLearningSupportTablesAndConstraints() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V6__learning_support.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(
				input.readAllBytes(),
				StandardCharsets.UTF_8
			);
		}

		assertThat(migration)
			.contains("CREATE TABLE quiz_assessments")
			.contains("CREATE TABLE diagnoses")
			.contains("CREATE TABLE repair_results")
			.contains("CREATE TABLE learner_memories")
			.contains("CREATE TABLE learner_memory_candidates")
			.contains("UNIQUE (quiz_submission_id)")
			.contains("UNIQUE (diagnosis_id)")
			.contains("UNIQUE (user_id, material_id)")
			.contains("CHECK (confidence >= 0 AND confidence <= 1)")
			.contains("ADD COLUMN pending_diagnosis_id BIGINT NULL");

		assertThat(migration)
			.doesNotContain("FOREIGN KEY (pending_diagnosis_id)");
	}
}
