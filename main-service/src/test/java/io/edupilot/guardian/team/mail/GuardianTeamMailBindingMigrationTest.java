package io.edupilot.guardian.team.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-team-mail-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
class GuardianTeamMailBindingMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void purposeConstraintUniqueBindingAndRequestForeignKeyDoNotAlterExistingMail() throws Exception {
		try (Connection connection = jdbc.getDataSource().getConnection(); Statement sql = connection.createStatement()) {
			installPrerequisites(connection, sql);
			// MySQL 정본 DDL의 CHECK 제거 문법만 H2 문법으로 바꾸며 제약 내용은 유지합니다.
			String canonical = new ClassPathResource("db/migration/V63__guardian_team_mail_binding.sql")
				.getContentAsString(StandardCharsets.UTF_8);
			ScriptUtils.executeSqlScript(connection,
				new ByteArrayResource(canonical.replace("DROP CHECK", "DROP CONSTRAINT").getBytes(StandardCharsets.UTF_8)));
			assertInvariants(sql);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_TEAM_MYSQL_URL",
		matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_team_synthetic(?:\\?.*)?$")
	void canonicalMysqlMigrationEnforcesPurposeBindingAndPreservesExistingQuotaHistory() throws Exception {
		try (Connection connection = DriverManager.getConnection(
			"jdbc:mysql://127.0.0.1:33316/guardian_mail_migration_synthetic", "root", "");
			Statement sql = connection.createStatement()) {
			try (var identity = sql.executeQuery("select @@port,database()")) {
				assertThat(identity.next()).isTrue();
				assertThat(identity.getInt(1)).isEqualTo(33316);
				assertThat(identity.getString(2)).isEqualTo("guardian_mail_migration_synthetic");
			}
			installPrerequisites(connection, sql);
			ScriptUtils.executeSqlScript(connection,
				new ClassPathResource("db/migration/V63__guardian_team_mail_binding.sql"));
			assertInvariants(sql);
		}
	}

	private void installPrerequisites(Connection connection, Statement sql) throws Exception {
		sql.execute("drop table if exists guardian_team_mail_bindings");
		sql.execute("drop table if exists email_send_reservations");
		sql.execute("drop table if exists email_quota_lock");
		sql.execute("drop table if exists email_outbox");
		sql.execute("drop table if exists email_deliveries");
		sql.execute("drop table if exists guardian_team_requests");
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V43__email_deliveries.sql"));
		sql.execute("insert into email_deliveries(id,recipient,type,status,subject,created_at,attempt_count) "
			+ "values(1,'unrelated@example.test','TEST','SENT','합성 일반 메일',current_timestamp,2)");
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V54__durable_email_outbox.sql"));
		// V63의 FK 경계를 독립 검증합니다. 전체 V61 이후 순서는 부모 통합 검증이 소유합니다.
		sql.execute("create table guardian_team_requests(id varchar(36) not null primary key)");
		sql.execute("insert into guardian_team_requests(id) values "
			+ "('5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141'),('b6e7d648-230a-4d25-87f1-377d8392ac1a')");
	}

	private void assertInvariants(Statement sql) throws SQLException {
		try (var existing = sql.executeQuery("select recipient,type,status,subject from email_deliveries where id=1")) {
			assertThat(existing.next()).isTrue();
			assertThat(existing.getString(1)).isEqualTo("unrelated@example.test");
			assertThat(existing.getString(2)).isEqualTo("TEST");
			assertThat(existing.getString(3)).isEqualTo("SENT");
			assertThat(existing.getString(4)).isEqualTo("합성 일반 메일");
		}
		try (var quota = sql.executeQuery("select sum(units) from email_send_reservations")) {
			assertThat(quota.next()).isTrue();
			assertThat(quota.getInt(1)).isEqualTo(2);
		}
		sql.execute("insert into email_deliveries(id,recipient,type,status,subject,created_at) "
			+ "values(2,'guardian@example.test','GUARDIAN_TEAM_NOTICE','QUEUED','합성 안내',current_timestamp)");
		assertThatThrownBy(() -> sql.execute("update email_deliveries set type='UNSUPPORTED' where id=2"))
			.isInstanceOf(SQLException.class);
		sql.execute("insert into guardian_team_mail_bindings(delivery_id,request_id,bound_at) "
			+ "values(2,'5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141',current_timestamp)");
		assertThatThrownBy(() -> sql.execute("insert into guardian_team_mail_bindings(delivery_id,request_id,bound_at) "
			+ "values(2,'b6e7d648-230a-4d25-87f1-377d8392ac1a',current_timestamp)"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> sql.execute("insert into guardian_team_mail_bindings(delivery_id,request_id,bound_at) "
			+ "values(999,'5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141',current_timestamp)"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> sql.execute("insert into guardian_team_mail_bindings(delivery_id,request_id,bound_at) "
			+ "values(1,'55555555-5555-4555-8555-555555555555',current_timestamp)"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> sql.execute("delete from guardian_team_requests where id='5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141'"))
			.isInstanceOf(SQLException.class);
		sql.execute("delete from email_deliveries where id=2");
		try (var bindings = sql.executeQuery("select count(*) from guardian_team_mail_bindings")) {
			assertThat(bindings.next()).isTrue();
			assertThat(bindings.getInt(1)).isZero();
		}
		sql.execute("delete from guardian_team_requests where id='5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141'");
	}
}
