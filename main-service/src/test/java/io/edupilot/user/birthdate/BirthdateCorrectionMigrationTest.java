package io.edupilot.user.birthdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@JdbcTest(properties = {"spring.datasource.url=jdbc:h2:mem:birthdate-correction-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(statements = {
	"drop table if exists birthdate_correction_requests", "drop table if exists users",
	"create table users (id bigint primary key, date_of_birth date, access_cohort varchar(24), age_verification_state varchar(30))",
	"insert into users values (1, '2012-12-31', 'NEW_SIGNUP', 'UNKNOWN')"
})
@Sql(scripts = "classpath:db/migration/V60__birthdate_correction_intake.sql")
class BirthdateCorrectionMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test void migrationPreservesDobAndEvidenceAndEnforcesUniqueValidIntake() {
		assertThat(jdbc.queryForObject("select date_of_birth from users where id=1", java.sql.Date.class).toLocalDate()).hasYear(2012);
		assertThat(jdbc.queryForObject("select access_cohort from users where id=1", String.class)).isEqualTo("NEW_SIGNUP");
		assertThat(jdbc.queryForObject("select age_verification_state from users where id=1", String.class)).isEqualTo("UNKNOWN");
		jdbc.update("insert into birthdate_correction_requests(user_id,requested_date_of_birth,state,requested_at) values(1,'2011-12-31','PENDING','2026-10-05 00:00:00')");
		assertThatThrownBy(() -> jdbc.update("insert into birthdate_correction_requests(user_id,requested_date_of_birth,state,requested_at) values(1,'2010-01-01','PENDING','2026-10-05 00:00:00')"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update birthdate_correction_requests set requested_date_of_birth=null"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update birthdate_correction_requests set state='APPROVED'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		jdbc.update("update birthdate_correction_requests set requested_date_of_birth=null,state='WITHDRAWN'");
		assertThat(jdbc.queryForObject("select requested_date_of_birth from birthdate_correction_requests", java.sql.Date.class)).isNull();
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BIRTHDATE_CORRECTION_MYSQL_URL", matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/birthdate_correction_synthetic(?:\\?.*)?$")
	void actualMysqlMigratesThroughV60AndPreservesExistingDobCohortAndEvidence() throws Exception {
		String url = "jdbc:mysql://127.0.0.1:33316/birthdate_full_migration_synthetic";
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement(); var identity = sql.executeQuery("select @@port,database()")) {
			assertThat(identity.next()).isTrue(); assertThat(identity.getInt(1)).isEqualTo(33316);
			assertThat(identity.getString(2)).isEqualTo("birthdate_full_migration_synthetic");
		}
		var before = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").target("59").load();
		before.migrate(); assertThat(before.validateWithResult().validationSuccessful).isTrue();
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			sql.execute("insert into users(email,password_hash,name,role,status,date_of_birth,access_cohort,age_verification_state,email_verification_state) values('synthetic-migration@example.com','synthetic-hash','Synthetic','LEARNER','ACTIVE','2012-12-31','LEGACY_EXEMPT','MANUAL_PENDING','UNKNOWN')");
		}
		var after = org.flywaydb.core.Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").target("60").load();
		after.migrate(); assertThat(after.validateWithResult().validationSuccessful).isTrue();
		assertThat(after.info().current().getVersion().getVersion()).isEqualTo("60");
		try (var connection = DriverManager.getConnection(url, "root", ""); var sql = connection.createStatement()) {
			long id;
			try (var row = sql.executeQuery("select id,date_of_birth,access_cohort,age_verification_state,email_verification_state from users where email='synthetic-migration@example.com'")) {
				assertThat(row.next()).isTrue(); id = row.getLong(1); assertThat(row.getDate(2).toLocalDate()).hasYear(2012);
				assertThat(row.getString(3)).isEqualTo("LEGACY_EXEMPT"); assertThat(row.getString(4)).isEqualTo("MANUAL_PENDING"); assertThat(row.getString(5)).isEqualTo("UNKNOWN");
			}
			sql.execute("insert into birthdate_correction_requests(user_id,requested_date_of_birth,state,requested_at) values(" + id + ",'2011-12-31','PENDING','2026-10-05 00:00:00')");
			assertThatThrownBy(() -> sql.execute("update birthdate_correction_requests set state='APPROVED'" )).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> sql.execute("update birthdate_correction_requests set requested_date_of_birth=null" )).isInstanceOf(SQLException.class);
			sql.execute("update birthdate_correction_requests set requested_date_of_birth=null,state='WITHDRAWN'");
		}
	}
}
