package io.edupilot.mail;

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

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:email-delivery-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(scripts = "classpath:db/migration/V43__email_deliveries.sql")
class EmailDeliveryMigrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void v43RunsInMysqlModeWithoutBodyColumns() {
		List<String> columns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_name = 'EMAIL_DELIVERIES'",
			String.class
		);
		assertThat(columns).contains(
			"RECIPIENT", "TYPE", "STATUS", "SUBJECT", "PROVIDER_MESSAGE_ID",
			"ERROR_SUMMARY", "ATTEMPT_COUNT", "CREATED_AT", "SENT_AT"
		).doesNotContain("BODY", "TEXT_BODY", "HTML_BODY");
		List<String> indexes = jdbcTemplate.queryForList(
			"select index_name from information_schema.indexes "
				+ "where table_name = 'EMAIL_DELIVERIES'",
			String.class
		);
		assertThat(indexes).contains(
			"IDX_EMAIL_DELIVERIES_RECIPIENT_CREATED",
			"IDX_EMAIL_DELIVERIES_STATUS_CREATED"
		);
		jdbcTemplate.update("""
			insert into email_deliveries(recipient, type, status, subject, attempt_count, created_at)
			values ('user@example.com', 'TEST', 'QUEUED', 'test', 0, current_timestamp)
			""");
		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into email_deliveries(recipient, type, status, subject, attempt_count, created_at)
			values ('user@example.com', 'OTHER', 'QUEUED', 'test', 0, current_timestamp)
			""")).isInstanceOf(DataIntegrityViolationException.class);
	}
}
