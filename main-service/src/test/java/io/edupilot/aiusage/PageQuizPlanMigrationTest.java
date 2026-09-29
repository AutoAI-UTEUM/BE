package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:page-quiz-plan-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@Sql(scripts = {
	"classpath:db/migration/V36__ai_usage_log.sql",
	"classpath:db/migration/V50__ai_usage_quiz_decision_source.sql"
})
class PageQuizPlanMigrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;

	@Test
	void historicalRowsRemainNullAndNewTurnsCanStoreSource() {
		jdbcTemplate.update("""
			insert into ai_usage_log (user_id, feature, success)
			values (1, 'TURN', true)
			""");
		jdbcTemplate.update("""
			insert into ai_usage_log
			(user_id, feature, success, quiz_decision_source)
			values (2, 'TURN', true, 'PLAN')
			""");

		assertThat(jdbcTemplate.queryForList(
			"select quiz_decision_source from ai_usage_log order by id",
			String.class)).containsExactly(null, "PLAN");
	}
}
