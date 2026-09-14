package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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
	"spring.datasource.url=jdbc:h2:mem:exam-manual-score-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = {
		"create table exam_answers (id bigint primary key, "
			+ "score decimal(10, 2) null, max_score decimal(10, 2) not null)",
		"insert into exam_answers(id, score, max_score) values (1, 5.00, 10.00)"
	}),
	@Sql(scripts = "classpath:db/migration/V40__exam_answer_manual_scores.sql")
})
class ExamManualScoreMigrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v40RunsInMysqlModeAndPreservesOriginalScores() {
		List<String> columns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_name = 'EXAM_ANSWERS'",
			String.class
		);

		assertThat(columns).contains("MANUAL_SCORE", "ADJUSTED_BY", "ADJUSTED_AT");
		assertThat(jdbcTemplate.queryForObject(
			"select score from exam_answers where id = 1", String.class
		)).isEqualTo("5.00");
		assertThat(jdbcTemplate.queryForObject(
			"select manual_score from exam_answers where id = 1", String.class
		)).isNull();

		jdbcTemplate.update(
			"update exam_answers set manual_score = 8.00, adjusted_by = 2, "
				+ "adjusted_at = timestamp '2026-08-03 00:00:00' where id = 1"
		);
		assertThat(jdbcTemplate.queryForObject(
			"select score from exam_answers where id = 1", String.class
		)).isEqualTo("5.00");
		assertThatThrownBy(() -> jdbcTemplate.update(
			"update exam_answers set manual_score = 10.01 where id = 1"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}
}
