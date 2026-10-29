package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {"spring.datasource.url=jdbc:h2:mem:guardian-web-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(statements = {"drop table if exists guardian_web_requests", "drop table if exists users",
	"create table users(id bigint primary key)", "insert into users values(1)"})
@Sql(scripts = "classpath:db/migration/V59__guardian_web_consent_phone_intake.sql")
class GuardianWebMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void migrationRejectsDuplicateLinkAndFabricatedPhoneProof() {
		jdbc.update(insert("synthetic-link-1", "a", "AWAITING_CONSENT"));
		assertThatThrownBy(() -> jdbc.update(insert("synthetic-link-2", "a", "AWAITING_CONSENT")))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update guardian_web_requests set state='PHONE_CONFIRMED' where id='synthetic-link-1'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update guardian_web_requests set state='VERIFIED' where id='synthetic-link-1'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update guardian_web_requests set code_attempts=11 where id='synthetic-link-1'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_WEB_MYSQL", matches = "true")
	void actualMysqlValidatesAvailableMigrationChainAndKeepsAccountEvidenceUnknown() throws Exception {
		String url = "jdbc:mysql://127.0.0.1:33316/guardian_web_full_migration_synthetic";
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement();
			var identity = sql.executeQuery("select @@port,database()")) {
			assertThat(identity.next()).isTrue(); assertThat(identity.getInt(1)).isEqualTo(33316);
			assertThat(identity.getString(2)).isEqualTo("guardian_web_full_migration_synthetic");
		}
		var before = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "")
			.locations("classpath:db/migration").target("58").cleanDisabled(false).load();
		// Only the hardcoded, identity-checked disposable schema is recreated.
		before.clean(); before.migrate();
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			sql.execute("insert into users(id,email,password_hash,name,role,status,auth_provider,created_at,updated_at) values(1,'synthetic-guardian-migration@example.com','hash','Synthetic','LEARNER','ACTIVE','LOCAL',current_timestamp,current_timestamp)");
		}
		var after = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "")
			.locations("classpath:db/migration").target("59").load();
		after.migrate(); assertThat(after.validateWithResult().validationSuccessful).isTrue();
		assertThat(after.info().current().getVersion().getVersion()).isEqualTo("59");
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			sql.execute(insert("synthetic-link-1", "a", "AWAITING_CONSENT"));
			assertThatThrownBy(() -> sql.execute(insert("synthetic-link-2", "a", "AWAITING_CONSENT")))
				.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> sql.execute("update guardian_web_requests set state='PHONE_CONFIRMED' where id='synthetic-link-1'"))
				.isInstanceOf(SQLException.class);
			try (var row = sql.executeQuery("select access_cohort,date_of_birth,age_verification_state,email_verification_state,email_verified_at from users where id=1")) {
				assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("NEW_SIGNUP");
				assertThat(row.getDate(2)).isNull(); assertThat(row.getString(3)).isEqualTo("UNKNOWN");
				assertThat(row.getString(4)).isEqualTo("UNKNOWN"); assertThat(row.getTimestamp(5)).isNull();
			}
			try (var columns = sql.executeQuery("select column_name from information_schema.columns where table_schema=database() and table_name='guardian_web_requests'")) {
				var names = new java.util.ArrayList<String>(); while (columns.next()) { names.add(columns.getString(1)); }
				assertThat(names).doesNotContain("phone", "code", "raw_token").contains("token_hash", "phone_fingerprint", "notice_digest");
			}
		}
	}

	private static String insert(String id, String hashCharacter, String state) {
		return "insert into guardian_web_requests(id,user_id,token_hash,notice_version,notice_digest,state,issued_at,expires_at) values('"
			+ id + "',1,'" + hashCharacter.repeat(64) + "','synthetic-v1','" + "a".repeat(64) + "','" + state
			+ "',current_timestamp,current_timestamp)";
	}
}
