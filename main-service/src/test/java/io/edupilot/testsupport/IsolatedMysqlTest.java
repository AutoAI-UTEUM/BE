package io.edupilot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IsolatedMysqlTest {
	private static final String SCHEMA = "guardian_team_full_migration_synthetic";
	private Map<String, String> environment() {
		return new HashMap<>(Map.of("GUARDIAN_MYSQL_ISOLATED", "true", "GITHUB_ACTIONS", "true",
			"GUARDIAN_MYSQL_SERVER_UUID", "12345678-1234-1234-1234-123456789abc"));
	}

	@Test
	void cannotSelectAnExternalEndpointThroughEnvironment() {
		var environment = environment();
		environment.put("GUARDIAN_TEAM_MYSQL_URL", "jdbc:mysql://external.invalid:3306/production");
		assertThat(IsolatedMysql.validatedUrl(SCHEMA, environment))
			.isEqualTo("jdbc:mysql://guardian-mysql:3306/guardian_team_full_migration_synthetic");
	}

	@Test
	void rejectsUnknownSchemaBeforeAnyJdbcConnection() {
		assertThatThrownBy(() -> IsolatedMysql.validatedUrl("production", environment()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requiresEveryRunnerMarkerBeforeAnyJdbcConnection() {
		for (String marker : environment().keySet()) {
			var environment = environment();
			environment.remove(marker);
			assertThatThrownBy(() -> IsolatedMysql.validatedUrl(SCHEMA, environment))
				.isInstanceOf(IllegalStateException.class);
		}
	}
}
