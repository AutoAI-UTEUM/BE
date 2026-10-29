package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlGroup;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:exam-manual-score-reason-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = {
		"create table exam_answers (id bigint primary key, score decimal(10, 2) null, max_score decimal(10, 2) not null)",
		"insert into exam_answers(id, score, max_score) values (1, 5.00, 10.00)"
	}),
	@Sql(scripts = "classpath:db/migration/V40__exam_answer_manual_scores.sql"),
	@Sql(statements = "update exam_answers set manual_score = 8.00, adjusted_by = 2, "
		+ "adjusted_at = timestamp '2026-08-03 00:00:00' where id = 1"),
	@Sql(scripts = "classpath:db/migration/V64__exam_answer_manual_score_reason.sql")
})
class ExamManualScoreReasonMigrationTest {
	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void v64PreservesExistingAdjustmentAndAddsNullableBoundedUnicodeReason() {
		assertThat(jdbcTemplate.queryForObject(
			"select score from exam_answers where id = 1", String.class)).isEqualTo("5.00");
		assertThat(jdbcTemplate.queryForObject(
			"select manual_score from exam_answers where id = 1", String.class)).isEqualTo("8.00");
		assertThat(jdbcTemplate.queryForObject(
			"select adjusted_by from exam_answers where id = 1", Long.class)).isEqualTo(2L);
		assertThat(jdbcTemplate.queryForObject(
			"select manual_score_reason from exam_answers where id = 1", String.class)).isNull();
		String reason = "가".repeat(500);
		jdbcTemplate.update("update exam_answers set manual_score_reason = ? where id = 1", reason);
		assertThat(jdbcTemplate.queryForObject(
			"select manual_score_reason from exam_answers where id = 1", String.class)).isEqualTo(reason);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"update exam_answers set manual_score_reason = ? where id = 1", "가".repeat(501)))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbcTemplate.update("update exam_answers set manual_score_reason = null where id = 1");
		assertThat(jdbcTemplate.queryForObject(
			"select manual_score_reason from exam_answers where id = 1", String.class)).isNull();
	}
}
