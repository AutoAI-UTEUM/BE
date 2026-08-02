package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ExamMigrationContractTest {

	@Test
	void v17ContainsExamDomainContract() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V17__exam.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("CREATE TABLE exams")
			.contains("CREATE TABLE exam_questions")
			.contains("CREATE TABLE exam_submissions")
			.contains("CREATE TABLE exam_answers")
			.contains("uk_exam_questions_exam_question_no")
			.contains("uk_exam_submissions_exam_user_attempt")
			.contains("uk_exam_submissions_exam_user_request")
			.contains("uk_exam_answers_submission_question")
			.contains("CHECK (total_score >= 0)")
			.contains("score IS NULL OR (score >= 0 AND score <= max_score)")
			.contains("normalized_score IS NULL")
			.contains("verdict IS NULL OR verdict IN ('CORRECT', 'PARTIAL', 'WRONG')")
			.doesNotContain("ON DELETE CASCADE");
	}
}
