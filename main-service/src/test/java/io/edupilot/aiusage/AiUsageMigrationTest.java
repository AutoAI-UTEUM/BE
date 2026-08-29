package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:ai-usage-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(scripts = "classpath:db/migration/V35__ai_usage_log.sql")
class AiUsageMigrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void createsNullableUsageColumnsAndQuotaIndexes() {
		jdbcTemplate.update(
			"""
				insert into ai_usage_log
				(user_id, feature, success)
				values (?, ?, ?)
				""",
			1L,
			AiFeature.EXTRACT.name(),
			false
		);

		MapRow row = jdbcTemplate.queryForObject(
			"""
				select user_id, feature, model, input_tokens,
				       output_tokens, reasoning_tokens, success
				from ai_usage_log
				""",
			(resultSet, rowNumber) -> new MapRow(
				resultSet.getLong("user_id"),
				resultSet.getString("feature"),
				resultSet.getString("model"),
				resultSet.getObject("input_tokens"),
				resultSet.getObject("output_tokens"),
				resultSet.getObject("reasoning_tokens"),
				resultSet.getBoolean("success")
			)
		);
		assertThat(row).isEqualTo(new MapRow(
			1L,
			AiFeature.EXTRACT.name(),
			null,
			null,
			null,
			null,
			false
		));

		List<String> indexNames = jdbcTemplate.queryForList(
			"""
				select index_name
				from information_schema.indexes
				where table_name = 'AI_USAGE_LOG'
				""",
			String.class
		);
		assertThat(indexNames).contains(
			"IDX_AI_USAGE_USER_DAY",
			"IDX_AI_USAGE_FEATURE"
		);
	}

	private record MapRow(
		long userId,
		String feature,
		String model,
		Object inputTokens,
		Object outputTokens,
		Object reasoningTokens,
		boolean success
	) {
	}
}
