package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:page-records;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SessionPageRecordRepository.class)
@Sql(scripts = "/session-page-record-test-schema.sql")
class SessionPageRecordRepositoryTest {

	@Autowired
	private SessionPageRecordRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearTables() {
		jdbcTemplate.update("DELETE FROM session_page_records");
		jdbcTemplate.update("DELETE FROM learning_sessions");
	}

	@Test
	void reExplanationKeepsOneRowAndRefreshesExplainedAt() {
		Instant first = Instant.parse("2026-08-01T10:00:00Z");
		Instant second = Instant.parse("2026-08-01T11:00:00Z");

		repository.upsertExplainedPage(100L, 3, first);
		repository.upsertExplainedPage(100L, 3, second);

		assertThat(repository.countBySessionId(100L)).isEqualTo(1);
		Timestamp explainedAt = jdbcTemplate.queryForObject(
			"SELECT explained_at FROM session_page_records",
			Timestamp.class
		);
		assertThat(explainedAt).isNotNull();
		assertThat(explainedAt.toInstant()).isEqualTo(second);
	}

	@Test
	void materialCountUsesPageUnionAndExcludesDeletedSessions() {
		insertSession(100L, 1L, 20L, "ACTIVE");
		insertSession(101L, 1L, 20L, "COMPLETED");
		insertSession(102L, 1L, 20L, "DELETED");
		insertSession(103L, 2L, 20L, "ACTIVE");
		Instant now = Instant.parse("2026-08-01T12:00:00Z");
		repository.upsertExplainedPage(100L, 1, now);
		repository.upsertExplainedPage(100L, 2, now);
		repository.upsertExplainedPage(101L, 2, now);
		repository.upsertExplainedPage(101L, 3, now);
		repository.upsertExplainedPage(102L, 4, now);
		repository.upsertExplainedPage(103L, 5, now);

		assertThat(repository.countDistinctByUserIdAndMaterialId(1L, 20L))
			.isEqualTo(3);
	}

	private void insertSession(
		Long id,
		Long userId,
		Long materialId,
		String status
	) {
		jdbcTemplate.update(
			"""
			INSERT INTO learning_sessions (id, user_id, material_id, status)
			VALUES (?, ?, ?, ?)
			""",
			id,
			userId,
			materialId,
			status
		);
	}
}
