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

@JdbcTest(properties = {"spring.datasource.url=jdbc:h2:mem:legacy-access-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(statements = {"drop table if exists users",
	"create table users(id bigint primary key,email varchar(255) not null,date_of_birth date,age_verification_state varchar(30) default 'UNKNOWN',email_verification_state varchar(20) default 'UNKNOWN',email_verified_at timestamp)",
	"insert into users(id,email) values(1,'synthetic-existing@example.com')"})
@Sql(scripts = "classpath:db/migration/V58__legacy_account_access_cohort.sql")
class LegacyAccountAccessMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void migrationMarksOnlyExistingRowsAndPreservesAllUnknownEvidence() {
		assertThat(jdbc.queryForObject("select access_cohort from users where id=1", String.class)).isEqualTo("LEGACY_EXEMPT");
		assertThat(jdbc.queryForObject("select date_of_birth from users where id=1", java.sql.Date.class)).isNull();
		assertThat(jdbc.queryForObject("select age_verification_state from users where id=1", String.class)).isEqualTo("UNKNOWN");
		assertThat(jdbc.queryForObject("select email_verification_state from users where id=1", String.class)).isEqualTo("UNKNOWN");
		assertThat(jdbc.queryForObject("select email_verified_at from users where id=1", java.sql.Timestamp.class)).isNull();
		jdbc.update("insert into users(id,email) values(2,'synthetic-new@example.com')");
		assertThat(jdbc.queryForObject("select access_cohort from users where id=2", String.class)).isEqualTo("NEW_SIGNUP");
		assertThatThrownBy(() -> jdbc.update("update users set access_cohort='VERIFIED' where id=1"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "LEGACY_ACCESS_MYSQL", matches = "true")
	void actualMysqlMigratesTheFullChainAndDoesNotGrandfatherNewInserts() throws Exception {
		String url = "jdbc:mysql://127.0.0.1:33316/legacy_access_full_migration_synthetic";
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement();
			var identity = sql.executeQuery("select @@port,database()")) {
			assertThat(identity.next()).isTrue();
			assertThat(identity.getInt(1)).isEqualTo(33316);
			assertThat(identity.getString(2)).isEqualTo("legacy_access_full_migration_synthetic");
		}
		var before = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "")
			.locations("classpath:db/migration").target("57").cleanDisabled(false).load();
		// The hardcoded, identity-checked synthetic schema is recreated for repeatable migration evidence.
		before.clean();
		before.migrate();
		assertThat(before.validateWithResult().validationSuccessful).isTrue();
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			sql.execute("insert into users(email,password_hash,name,role,status,auth_provider,created_at,updated_at) values('synthetic-legacy-migration@example.com','hash','Synthetic','LEARNER','ACTIVE','LOCAL',current_timestamp,current_timestamp)");
		}
		var after = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "")
			.locations("classpath:db/migration").target("58").load();
		after.migrate();
		assertThat(after.validateWithResult().validationSuccessful).isTrue();
		assertThat(after.info().current().getVersion().getVersion()).isEqualTo("58");
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			try (var row = sql.executeQuery("select access_cohort,date_of_birth,age_verification_state,email_verification_state,email_verified_at from users where email='synthetic-legacy-migration@example.com'")) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString(1)).isEqualTo("LEGACY_EXEMPT");
				assertThat(row.getDate(2)).isNull();
				assertThat(row.getString(3)).isEqualTo("UNKNOWN");
				assertThat(row.getString(4)).isEqualTo("UNKNOWN");
				assertThat(row.getTimestamp(5)).isNull();
			}
			sql.execute("insert into users(email,password_hash,name,role,status,auth_provider,created_at,updated_at) values('synthetic-new-migration@example.com','hash','Synthetic','LEARNER','ACTIVE','LOCAL',current_timestamp,current_timestamp)");
			try (var row = sql.executeQuery("select access_cohort from users where email='synthetic-new-migration@example.com'")) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString(1)).isEqualTo("NEW_SIGNUP");
			}
			assertThatThrownBy(() -> sql.execute("update users set access_cohort='VERIFIED' where email='synthetic-legacy-migration@example.com'"))
				.isInstanceOf(SQLException.class);
		}
	}
}
