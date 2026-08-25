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
		jdbcTemplate.update("DELETE FROM classroom_members");
	}

	@Test
	void reExplanationKeepsOneRowAndRefreshesExplainedAt() {
		Instant first = Instant.parse("2026-08-01T10:00:00Z");
		Instant second = Instant.parse("2026-08-01T11:00:00Z");
		Instant originalCreatedAt = Instant.parse("2026-07-01T09:00:00Z");
		Instant originalUpdatedAt = Instant.parse("2026-07-01T10:00:00Z");

		repository.upsertExplainedPage(100L, 3, first);
		jdbcTemplate.update(
			"""
			UPDATE session_page_records
			SET created_at = ?, updated_at = ?
			""",
			Timestamp.from(originalCreatedAt),
			Timestamp.from(originalUpdatedAt)
		);
		repository.upsertExplainedPage(100L, 3, second);

		assertThat(repository.countBySessionId(100L)).isEqualTo(1);
		Timestamp explainedAt = jdbcTemplate.queryForObject(
			"SELECT explained_at FROM session_page_records",
			Timestamp.class
		);
		assertThat(explainedAt).isNotNull();
		assertThat(explainedAt.toInstant()).isEqualTo(second);
		Timestamp createdAt = jdbcTemplate.queryForObject(
			"SELECT created_at FROM session_page_records",
			Timestamp.class
		);
		Timestamp updatedAt = jdbcTemplate.queryForObject(
			"SELECT updated_at FROM session_page_records",
			Timestamp.class
		);
		assertThat(createdAt).isNotNull();
		assertThat(createdAt.toInstant()).isEqualTo(originalCreatedAt);
		assertThat(updatedAt).isNotNull();
		assertThat(updatedAt.toInstant()).isEqualTo(second);
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

	@Test
	void classroomProgressBatchExcludesOtherClassroomsAndMaterials() {
		insertMember(30L, 1L);
		insertMember(30L, 2L);
		insertMember(31L, 3L);
		insertSession(100L, 1L, 20L, "ACTIVE");
		insertSession(101L, 2L, 20L, "COMPLETED");
		insertSession(102L, 3L, 20L, "ACTIVE");
		insertSession(103L, 1L, 30L, "ACTIVE");
		Instant now = Instant.parse("2026-08-04T03:00:00Z");
		repository.upsertExplainedPage(100L, 1, now);
		repository.upsertExplainedPage(100L, 2, now);
		repository.upsertExplainedPage(101L, 1, now);
		repository.upsertExplainedPage(102L, 3, now);
		repository.upsertExplainedPage(103L, 4, now);

		assertThat(repository.findClassroomProgressCounts(30L, java.util.Set.of(20L)))
			.containsExactlyInAnyOrder(
				new SessionPageRecordRepository.UserMaterialProgressCount(1L, 20L, 2L),
				new SessionPageRecordRepository.UserMaterialProgressCount(2L, 20L, 1L)
			);
	}

	@Test
	void studentMaterialProgressBatchUsesDistinctPagesAndActiveSessionsOnly() {
		insertSession(100L, 1L, 20L, "ACTIVE");
		insertSession(101L, 1L, 20L, "COMPLETED");
		insertSession(102L, 1L, 20L, "DELETED");
		insertSession(103L, 1L, 30L, "ACTIVE");
		insertSession(104L, 2L, 20L, "ACTIVE");
		Instant now = Instant.parse("2026-08-04T03:00:00Z");
		repository.upsertExplainedPage(100L, 1, now);
		repository.upsertExplainedPage(100L, 2, now);
		repository.upsertExplainedPage(101L, 2, now);
		repository.upsertExplainedPage(101L, 3, now);
		repository.upsertExplainedPage(102L, 4, now);
		repository.upsertExplainedPage(103L, 1, now);
		repository.upsertExplainedPage(104L, 5, now);

		assertThat(repository.findStudentMaterialProgressCounts(
			1L,
			java.util.Set.of(20L, 30L)
		)).containsExactlyInAnyOrder(
			new SessionPageRecordRepository.MaterialProgressCount(20L, 3L),
			new SessionPageRecordRepository.MaterialProgressCount(30L, 1L)
		);
	}

	private void insertMember(Long classroomId, Long userId) {
		jdbcTemplate.update(
			"INSERT INTO classroom_members (classroom_id, user_id) VALUES (?, ?)",
			classroomId,
			userId
		);
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
