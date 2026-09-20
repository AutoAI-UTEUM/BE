package io.edupilot.auth;

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
	"spring.datasource.url=jdbc:h2:mem:auth-session-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = {
		"create table users (id bigint primary key)",
		"create table refresh_tokens (id bigint primary key, user_id bigint not null, "
			+ "token_hash char(64) not null, expires_at timestamp not null, "
			+ "revoked_at timestamp null, created_at timestamp not null)",
		"insert into users(id) values (1)",
		"insert into refresh_tokens(id, user_id, token_hash, expires_at, created_at) "
			+ "values (1, 1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', "
			+ "timestamp '2026-10-01 00:00:00', timestamp '2026-09-20 00:00:00')"
	}),
	@Sql(scripts = "classpath:db/migration/V41__auth_sessions.sql")
})
class AuthSessionMigrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v41CreatesSessionsAndKeepsLegacyRefreshTokensNullable() {
		List<String> sessionColumns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_name = 'AUTH_SESSIONS'",
			String.class
		);
		assertThat(sessionColumns).contains(
			"ID",
			"USER_ID",
			"LAST_ACTIVITY_AT",
			"IDLE_EXPIRES_AT",
			"ABSOLUTE_EXPIRES_AT",
			"REVOKED_AT",
			"CREATED_AT",
			"UPDATED_AT"
		);
		assertThat(jdbcTemplate.queryForObject(
			"select session_id from refresh_tokens where id = 1",
			Long.class
		)).isNull();

		jdbcTemplate.update(
			"insert into auth_sessions(id, user_id, last_activity_at, idle_expires_at, "
				+ "absolute_expires_at) values (10, 1, timestamp '2026-09-20 00:00:00', "
				+ "timestamp '2026-09-20 02:00:00', timestamp '2026-10-04 00:00:00')"
		);
		jdbcTemplate.update("update refresh_tokens set session_id = 10 where id = 1");
		assertThat(jdbcTemplate.queryForObject(
			"select session_id from refresh_tokens where id = 1",
			Long.class
		)).isEqualTo(10L);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into auth_sessions(id, user_id, last_activity_at, idle_expires_at, "
				+ "absolute_expires_at) values (11, 999, timestamp '2026-09-20 00:00:00', "
				+ "timestamp '2026-09-20 02:00:00', timestamp '2026-10-04 00:00:00')"
		)).isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into auth_sessions(id, user_id, last_activity_at, idle_expires_at, "
				+ "absolute_expires_at) values (12, 1, timestamp '2026-09-20 03:00:00', "
				+ "timestamp '2026-09-20 02:00:00', timestamp '2026-10-04 00:00:00')"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}
}
