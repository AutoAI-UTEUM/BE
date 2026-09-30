package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

class MaterialOverviewRetryGuardMigrationTest {

	@Test
	void v51RunsInH2MySqlModeAndPreservesExistingOverview() throws Exception {
		try (var connection = DriverManager.getConnection(
			"jdbc:h2:mem:material-overview-retry-migration;MODE=MySQL")) {
			try (var statement = connection.createStatement()) {
				statement.execute("""
					create table material_overviews (
					    id bigint primary key,
					    status varchar(20) not null
					)
					""");
				statement.execute("insert into material_overviews (id, status) values (1, 'READY')");
				String migration;
				try (var input = getClass().getResourceAsStream(
					"/db/migration/V51__material_overview_retry_guard.sql")) {
					assertThat(input).isNotNull();
					migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
				}
				for (String sql : migration.split(";")) {
					if (!sql.isBlank()) {
						statement.execute(sql);
					}
				}
				try (var rows = statement.executeQuery("""
					select status, generation_failure_count, generation_attempted_at
					from material_overviews where id = 1
					""")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getString(1)).isEqualTo("READY");
					assertThat(rows.getInt(2)).isZero();
					assertThat(rows.getTimestamp(3)).isNull();
				}
			}
		}
	}
}
