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
	"spring.datasource.url=jdbc:h2:mem:password-reset-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = "create table users (id bigint primary key)"),
	@Sql(scripts = "classpath:db/migration/V44__password_reset_tokens.sql")
})
class PasswordResetTokenMigrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void v44CreatesHashedSingleUseTokensWithForeignKeyAndIndex() {
		List<String> columns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_name = 'PASSWORD_RESET_TOKENS'", String.class
		);
		assertThat(columns).contains(
			"USER_ID", "TOKEN_HASH", "EXPIRES_AT", "USED_AT", "REQUESTED_IP", "CREATED_AT"
		).doesNotContain("TOKEN");
		assertThat(jdbcTemplate.queryForList(
			"select index_name from information_schema.indexes "
				+ "where table_name = 'PASSWORD_RESET_TOKENS'", String.class
		)).contains("IDX_PASSWORD_RESET_TOKENS_USER_CREATED");

		jdbcTemplate.update("insert into users(id) values (1)");
		String hash = "a".repeat(64);
		jdbcTemplate.update("""
			insert into password_reset_tokens(user_id, token_hash, expires_at, requested_ip, created_at)
			values (1, ?, current_timestamp, '192.0.2.1', current_timestamp)
			""", hash);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into password_reset_tokens(user_id, token_hash, expires_at, requested_ip, created_at)
			values (1, ?, current_timestamp, '192.0.2.1', current_timestamp)
			""", hash)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into password_reset_tokens(user_id, token_hash, expires_at, requested_ip, created_at)
			values (999, ?, current_timestamp, '192.0.2.1', current_timestamp)
			""", "b".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
	}
}
