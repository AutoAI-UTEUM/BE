package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
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
	"spring.datasource.url=jdbc:h2:mem:guardian-team-access-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
class GuardianTeamFullMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void accessMigrationPreservesExistingEvidenceAndRequiresExplicitPolicyForNewApproval() throws Exception {
		try (Connection connection = jdbc.getDataSource().getConnection(); Statement sql = connection.createStatement()) {
			sql.execute("create table users(id bigint primary key, date_of_birth date, access_cohort varchar(24), "
				+ "age_verification_state varchar(30) not null, email_verification_state varchar(20), email_verified_at datetime, "
				+ "constraint chk_user_age_verification_state check(age_verification_state in ('UNKNOWN','MANUAL_PENDING')))");
			sql.execute("insert into users values(1,'2012-12-31','LEGACY_EXEMPT','MANUAL_PENDING','UNKNOWN',null),"
				+ "(2,'2012-01-01','NEW_SIGNUP','UNKNOWN','VERIFIED','2026-10-06 00:00:00')");
			String ddl = new ClassPathResource("db/migration/V62__guardian_team_access_generation.sql")
				.getContentAsString(StandardCharsets.UTF_8);
			// CHECK removal syntax alone is adapted; canonical MySQL DDL runs below on a separate synthetic schema.
			ScriptUtils.executeSqlScript(connection,
				new ByteArrayResource(ddl.replace("DROP CHECK", "DROP CONSTRAINT").getBytes(StandardCharsets.UTF_8)));
			assertExistingEvidence(sql);
			assertApprovalConstraints(sql);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_TEAM_MYSQL_URL",
		matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_team_synthetic(?:\\?.*)?$")
	void canonicalMysqlMigratesV1ThroughV63WithoutApprovingExistingAccounts() throws Exception {
		String url = "jdbc:mysql://127.0.0.1:33316/guardian_team_full_migration_synthetic";
		try (Connection connection = DriverManager.getConnection(url, "root", ""); Statement sql = connection.createStatement()) {
			try (var identity = sql.executeQuery("select @@port,database(),@@datadir")) {
				assertThat(identity.next()).isTrue();
				assertThat(identity.getInt(1)).isEqualTo(33316);
				assertThat(identity.getString(2)).isEqualTo("guardian_team_full_migration_synthetic");
				assertThat(identity.getString(3).replace('\\', '/').replaceAll("/+$", ""))
					.isEqualTo("C:/Users/russe/Documents/Codex/2026-10-03/task-2/mysql-isolated");
			}
		}
		// The verified, dedicated synthetic schema is reset so reruns still exercise the V60 -> V63 transition.
		Flyway before = Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration")
			.target("60").cleanDisabled(false).load();
		before.clean();
		before.migrate();
		assertThat(before.validateWithResult().validationSuccessful).isTrue();
		try (Connection connection = DriverManager.getConnection(url, "root", ""); Statement sql = connection.createStatement()) {
			sql.execute("insert into users(id,email,password_hash,name,role,status,date_of_birth,access_cohort,"
				+ "age_verification_state,email_verification_state,email_verified_at) values"
				+ "(1,'legacy-migration@example.invalid','synthetic','Synthetic legacy','LEARNER','ACTIVE','2012-12-31','LEGACY_EXEMPT','MANUAL_PENDING','UNKNOWN',null),"
				+ "(2,'child-migration@example.invalid','synthetic','Synthetic child','LEARNER','ACTIVE','2012-01-01','NEW_SIGNUP','UNKNOWN','VERIFIED','2026-10-06 00:00:00')");
		}
		Flyway after = Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").load();
		after.migrate();
		assertThat(after.validateWithResult().validationSuccessful).isTrue();
		assertThat(after.info().current().getVersion().getVersion()).isEqualTo("63");
		try (Connection connection = DriverManager.getConnection(url, "root", ""); Statement sql = connection.createStatement()) {
			assertExistingEvidence(sql);
			assertApprovalConstraints(sql);
			assertThatThrownBy(() -> sql.execute("insert into guardian_team_requests(id,user_id,generation,revision,state,"
				+ "notice_version,notice_digest,configuration_digest,created_at,generation_started_at,request_expires_at,contact_origin,"
				+ "relationship_checked,legal_method_checked,first_collected_at,unconfirmed_erase_due_at) values"
				+ "('1e42ee3b-bfac-4878-a249-0f0d543a1eb7',2,1,1,'AWAITING_CONSENT','synthetic','" + "a".repeat(64) + "','"
				+ "b".repeat(64) + "','2026-10-06 00:00:00','2026-10-06 00:00:00','2026-10-11 00:00:00','CHILD',"
				+ "false,false,'2026-10-06 00:00:00','2026-10-12 00:00:00')")).isInstanceOf(SQLException.class);
			try (var rows = sql.executeQuery("select count(*) from guardian_team_requests")) {
				assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
			}
		}
	}

	private void assertExistingEvidence(Statement sql) throws SQLException {
		try (var rows = sql.executeQuery("select id,date_of_birth,access_cohort,age_verification_state,email_verification_state,"
			+ "guardian_approved_until,guardian_ai_consent_allowed,guardian_consent_epoch,guardian_approval_policy_digest,email_verified_at from users order by id")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getLong(1)).isEqualTo(1); assertThat(rows.getDate(2).toLocalDate()).isEqualTo(java.time.LocalDate.of(2012, 12, 31));
			assertThat(rows.getString(3)).isEqualTo("LEGACY_EXEMPT"); assertThat(rows.getString(4)).isEqualTo("MANUAL_PENDING");
			assertThat(rows.getString(5)).isEqualTo("UNKNOWN"); assertThat(rows.getTimestamp(10)).isNull(); assertNoGrant(rows);
			assertThat(rows.next()).isTrue();
			assertThat(rows.getLong(1)).isEqualTo(2); assertThat(rows.getDate(2).toLocalDate()).isEqualTo(java.time.LocalDate.of(2012, 1, 1));
			assertThat(rows.getString(3)).isEqualTo("NEW_SIGNUP"); assertThat(rows.getString(4)).isEqualTo("UNKNOWN");
			assertThat(rows.getString(5)).isEqualTo("VERIFIED");
			assertThat(rows.getTimestamp(10).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 6, 0, 0)); assertNoGrant(rows);
			assertThat(rows.next()).isFalse();
		}
	}

	private void assertNoGrant(java.sql.ResultSet rows) throws SQLException {
		assertThat(rows.getTimestamp(6)).isNull(); assertThat(rows.getBoolean(7)).isFalse();
		assertThat(rows.getLong(8)).isZero(); assertThat(rows.getString(9)).isNull();
	}

	private void assertApprovalConstraints(Statement sql) throws SQLException {
		assertThatThrownBy(() -> sql.execute("update users set age_verification_state='TEAM_APPROVED' where id=2"))
			.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> sql.execute("update users set age_verification_state='TEAM_APPROVED',"
			+ "guardian_approved_until='2026-10-07 00:00:00' where id=2")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> sql.execute("update users set guardian_consent_epoch=-1 where id=2"))
			.isInstanceOf(SQLException.class);
		sql.execute("update users set age_verification_state='TEAM_APPROVED',guardian_approved_until='2026-10-07 00:00:00',"
			+ "guardian_approval_policy_digest='" + "a".repeat(64) + "',guardian_consent_epoch=1 where id=2");
	}
}
