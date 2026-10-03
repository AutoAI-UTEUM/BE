package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {"spring.datasource.url=jdbc:h2:mem:mail-outbox-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(scripts = "classpath:db/migration/V43__email_deliveries.sql")
@Sql(statements = {
	"insert into email_deliveries(id,recipient,type,status,subject,created_at) values(1,'a@example.com','TEST','QUEUED','old pending',current_timestamp)",
	"insert into email_deliveries(id,recipient,type,status,subject,created_at) values(2,'b@example.com','TEST','SENT','old accepted',current_timestamp)",
	"insert into email_deliveries(id,recipient,type,status,subject,created_at) values(3,'c@example.com','TEST','FAILED','old failed',current_timestamp)"
})
@Sql(scripts = "classpath:db/migration/V54__durable_email_outbox.sql")
class EmailOutboxMigrationTest {
	@Autowired private JdbcTemplate jdbc;
	@Test void legacyPendingMailIsMarkedUnavailableWithoutInventingPayloadAndForeignKeyCascades() {
		assertThat(jdbc.queryForObject("select error_summary from email_deliveries where id=1", String.class)).isEqualTo("LEGACY_PAYLOAD_UNAVAILABLE");
		assertThat(jdbc.queryForObject("select status from email_deliveries where id=2", String.class)).isEqualTo("SENT");
		assertThat(jdbc.queryForObject("select status from email_deliveries where id=3", String.class)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select count(*) from email_outbox", Long.class)).isZero();
		jdbc.update("insert into email_outbox(delivery_id, status,next_attempt_at,expires_at,created_at) values(2,'SENT',current_timestamp,current_timestamp,current_timestamp)");
		jdbc.update("delete from email_deliveries where id=2");
		assertThat(jdbc.queryForObject("select count(*) from email_outbox", Long.class)).isZero();
	}
}
