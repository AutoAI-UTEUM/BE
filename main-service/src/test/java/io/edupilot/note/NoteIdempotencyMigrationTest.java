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

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {"spring.datasource.url=jdbc:h2:mem:note-idempotency-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(statements = {
	"create table notes(id bigint auto_increment primary key, user_id bigint not null, source_message_id bigint, content varchar(100))",
	"insert into notes(user_id,source_message_id,content) values (1,10,'legacy one'),(1,10,'legacy two')"
})
@Sql(scripts = "classpath:db/migration/V53__note_source_idempotency.sql")
class NoteIdempotencyMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void preservesLegacyDuplicatesAndEnforcesNewSourceKeysWithoutRestrictingManualNotes() {
		assertThat(jdbc.queryForObject("select count(*) from notes", Long.class)).isEqualTo(2);
		jdbc.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,11,11,'new')");
		assertThatThrownBy(() -> jdbc.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,11,11,'duplicate')"))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("insert into notes(user_id,content) values(1,'manual'),(1,'manual again')");
		assertThatThrownBy(() -> jdbc.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,12,13,'wrong source')"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
