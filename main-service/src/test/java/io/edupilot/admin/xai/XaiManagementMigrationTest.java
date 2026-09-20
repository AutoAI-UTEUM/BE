package io.edupilot.admin.xai;

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
import org.springframework.test.context.jdbc.SqlGroup;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:xai-management-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = {
		"create table users (id bigint primary key)",
		"create table ai_usage_log (id bigint auto_increment primary key)"
	}),
	@Sql(scripts = "classpath:db/migration/V42__xai_management_monitoring.sql")
})
class XaiManagementMigrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v41RunsInMysqlModeAndAddsFutureUsageAndAlertSchema() {
		List<String> usageColumns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_name = 'AI_USAGE_LOG'",
			String.class
		);
		assertThat(usageColumns).contains("COST_USD_TICKS", "REQUEST_ID");

		jdbcTemplate.update("insert into ai_usage_log(request_id) values (null)");
		jdbcTemplate.update("insert into ai_usage_log(request_id) values (null)");
		jdbcTemplate.update("insert into ai_usage_log(request_id) values ('request-1')");
		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into ai_usage_log(request_id) values ('request-1')"
		)).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(jdbcTemplate.queryForObject(
			"select balance_critical_usd from xai_alert_config where id = 1",
			String.class
		)).isEqualTo("10.0000");
		assertThat(jdbcTemplate.queryForObject(
			"select balance_warning_usd from xai_alert_config where id = 1",
			String.class
		)).isEqualTo("50.0000");
		assertThat(jdbcTemplate.queryForObject(
			"select daily_cost_warning_usd from xai_alert_config where id = 1",
			String.class
		)).isNull();
		assertThat(jdbcTemplate.queryForObject(
			"select depletion_critical_days from xai_alert_config where id = 1",
			Integer.class
		)).isEqualTo(7);
		assertThat(jdbcTemplate.queryForObject(
			"select depletion_warning_days from xai_alert_config where id = 1",
			Integer.class
		)).isEqualTo(30);

		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into xai_alert_config(
			  id, balance_critical_usd, balance_warning_usd,
			  daily_cost_warning_usd, depletion_critical_days,
			  depletion_warning_days, updated_by
			) values (2, 10, 50, null, 7, 30, null)
			""")).isInstanceOf(DataIntegrityViolationException.class);
	}
}
