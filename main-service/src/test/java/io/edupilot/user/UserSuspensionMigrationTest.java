package io.edupilot.user;

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
	"spring.datasource.url=jdbc:h2:mem:user-suspension-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = "create table users (id bigint primary key, "
		+ "status varchar(20) not null default 'ACTIVE', "
		+ "constraint chk_users_status check (status in ('ACTIVE', 'DELETED')))"),
	@Sql(scripts = "classpath:db/migration/V47__account_suspension.sql")
})
class UserSuspensionMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void v47PreservesDeletedAndAddsSuspensionMetadataAndConstraint() {
		jdbc.update("insert into users (id) values (1)");
		jdbc.update("insert into users (id, status) values (2, 'DELETED')");
		jdbc.update("""
			update users set status = 'SUSPENDED', suspended_reason = '운영 확인',
			  suspended_by = 9, suspended_at = current_timestamp where id = 1
			""");
		assertThat(jdbc.queryForObject("select status from users where id = 1", String.class))
			.isEqualTo("SUSPENDED");
		assertThat(jdbc.queryForObject("select status from users where id = 2", String.class))
			.isEqualTo("DELETED");
		assertThatThrownBy(() -> jdbc.update("update users set status = 'INVALID' where id = 1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
