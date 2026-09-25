package io.edupilot.note;

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
	"spring.datasource.url=jdbc:h2:mem:user-note-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = {
		"create table users (id bigint primary key)",
		"create table learning_materials (id bigint primary key)",
		"insert into users (id) values (1)",
		"insert into learning_materials (id) values (10)"
	}),
	@Sql(scripts = "classpath:db/migration/V46__user_notes_wrong_answer_notes.sql")
})
class UserNoteMigrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void createsSeparateNotesWithNullableClientIdsAndUniqueResultRef() {
		jdbcTemplate.update("""
			insert into user_notes
			(user_id, material_id, title, content, created_at, updated_at)
			values (1, 10, 'title', 'content', current_timestamp, current_timestamp)
			""");
		jdbcTemplate.update("""
			insert into user_notes
			(user_id, title, content, created_at, updated_at)
			values (1, 'second', 'content', current_timestamp, current_timestamp)
			""");
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from user_notes where user_id = 1", Integer.class
		)).isEqualTo(2);
		jdbcTemplate.update("delete from learning_materials where id = 10");
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from user_notes where material_id is null", Integer.class
		)).isEqualTo(2);

		jdbcTemplate.update("""
			insert into wrong_answer_notes
			(user_id, quiz_result_ref, question_snapshot, created_at, updated_at)
			values (1, '7:q1', '{}', current_timestamp, current_timestamp)
			""");
		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into wrong_answer_notes
			(user_id, quiz_result_ref, question_snapshot, created_at, updated_at)
			values (1, '7:q1', '{}', current_timestamp, current_timestamp)
			""")).isInstanceOf(DataIntegrityViolationException.class);
	}
}
