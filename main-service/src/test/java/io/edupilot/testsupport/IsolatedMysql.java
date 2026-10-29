package io.edupilot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/** CI-only endpoint. The runner supplies an internal Docker network, never an arbitrary JDBC URL. */
public final class IsolatedMysql {
	public static final String USER = "root";
	// Public disposable fixture credential; never used outside the job-local tmpfs service.
	public static final String PASSWORD = "guardian-job-only";
	private static final Set<String> SCHEMAS = Set.of(
		"guardian_consent_fence_mysql_synthetic", "guardian_admin_lock_jpa_synthetic",
		"guardian_team_full_migration_synthetic", "guardian_turn_persistence_mysql_synthetic");

	private IsolatedMysql() { }

	static String validatedUrl(String schema, Map<String, String> environment) {
		if (!SCHEMAS.contains(schema)
			|| !"true".equals(environment.get("GUARDIAN_MYSQL_ISOLATED"))
			|| !"true".equals(environment.get("GITHUB_ACTIONS"))
			|| !environment.getOrDefault("GUARDIAN_MYSQL_SERVER_UUID", "").matches("[0-9a-f-]{36}")) {
			throw new IllegalStateException("Requires the isolated GitHub Actions synthetic MySQL runner");
		}
		return "jdbc:mysql://guardian-mysql:3306/" + schema;
	}

	public static String url(String schema) {
		return validatedUrl(schema, System.getenv());
	}

	public static Connection open(String schema) throws SQLException {
		// Validate before the first JDBC call; there is no URL, host, port or schema override.
		Connection connection = DriverManager.getConnection(url(schema), USER, PASSWORD);
		try {
			try (var sql = connection.createStatement(); var identity = sql.executeQuery(
				"select @@port,database(),@@datadir,@@server_uuid,@@version")) {
				assertThat(identity.next()).isTrue();
				assertThat(identity.getInt(1)).isEqualTo(3306);
				assertThat(identity.getString(2)).isEqualTo(schema);
				assertThat(identity.getString(3)).isEqualTo("/var/lib/mysql/");
				assertThat(identity.getString(4)).isEqualTo(System.getenv("GUARDIAN_MYSQL_SERVER_UUID"));
				assertThat(identity.getString(5)).isEqualTo("8.4.11");
			}
			return connection;
		} catch (SQLException | RuntimeException | AssertionError failure) {
			connection.close();
			throw failure;
		}
	}

	public static void configure(DynamicPropertyRegistry registry, String schema) throws SQLException {
		try (var ignored = open(schema)) { /* Verify before Spring/Flyway can mutate this schema. */ }
		registry.add("spring.datasource.url", () -> url(schema));
		registry.add("spring.datasource.username", () -> USER);
		registry.add("spring.datasource.password", () -> PASSWORD);
	}

	public static void assertIdentity(JdbcTemplate jdbc, String schema) {
		assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(schema);
		assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(3306);
		assertThat(jdbc.queryForObject("select @@datadir", String.class)).isEqualTo("/var/lib/mysql/");
		assertThat(jdbc.queryForObject("select @@server_uuid", String.class)).isEqualTo(System.getenv("GUARDIAN_MYSQL_SERVER_UUID"));
	}
}
