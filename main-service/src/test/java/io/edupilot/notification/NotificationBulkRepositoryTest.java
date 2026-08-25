package io.edupilot.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import tools.jackson.databind.ObjectMapper;

class NotificationBulkRepositoryTest {

	@Test
	void classroomBulkInsertTargetsMembersOnly() {
		DataSource dataSource = dataSource("notification-members");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		createSchema(jdbc);
		jdbc.update("INSERT INTO users(id) VALUES (1), (2), (3), (4)");
		jdbc.update("INSERT INTO classroom_members(classroom_id, user_id) VALUES (30, 2), (30, 3), (31, 4)");
		NotificationBulkRepository repository = repository(dataSource);

		int inserted = repository.insertForClassroomMembers(
			30L,
			NotificationType.MATERIAL_UPLOADED,
			"New material",
			"Week 1 PDF",
			Map.of("classroomId", 30L, "materialId", 10L),
			Instant.parse("2026-08-14T03:00:00Z")
		);

		assertThat(inserted).isEqualTo(2);
		assertThat(jdbc.queryForList(
			"SELECT user_id FROM notifications ORDER BY user_id",
			Long.class
		)).containsExactly(2L, 3L);
	}

	@Test
	void cleanupDeletesOnlyRowsOlderThanThirtyDayBoundary() {
		DataSource dataSource = dataSource("notification-cleanup");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		createSchema(jdbc);
		jdbc.update("INSERT INTO users(id) VALUES (1)");
		Instant cutoff = Instant.parse("2026-07-15T03:00:00Z");
		insertNotification(jdbc, 1L, cutoff.minusSeconds(1));
		insertNotification(jdbc, 2L, cutoff);
		insertNotification(jdbc, 3L, cutoff.plusSeconds(1));

		int deleted = repository(dataSource).deleteExpired(cutoff, 100);

		assertThat(deleted).isEqualTo(1);
		assertThat(jdbc.queryForList(
			"SELECT id FROM notifications ORDER BY id",
			Long.class
		)).containsExactly(2L, 3L);
	}

	private DataSource dataSource(String name) {
		return new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.setName(name + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
			.build();
	}

	private NotificationBulkRepository repository(DataSource dataSource) {
		return new NotificationBulkRepository(
			new NamedParameterJdbcTemplate(dataSource),
			new ObjectMapper()
		);
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE classroom_members (classroom_id BIGINT NOT NULL, user_id BIGINT NOT NULL)");
		jdbc.execute("""
			CREATE TABLE notifications (
			    id BIGINT AUTO_INCREMENT PRIMARY KEY,
			    user_id BIGINT NOT NULL,
			    type VARCHAR(40) NOT NULL,
			    title VARCHAR(200) NOT NULL,
			    body CLOB NOT NULL,
			    link_json VARCHAR(1000) NOT NULL,
			    read_at TIMESTAMP NULL,
			    created_at TIMESTAMP NOT NULL
			)
			""");
	}

	private void insertNotification(JdbcTemplate jdbc, Long id, Instant createdAt) {
		jdbc.update("""
			INSERT INTO notifications (
			    id, user_id, type, title, body, link_json, created_at
			) VALUES (?, 1, 'MATERIAL_UPLOADED', 'title', 'body', '{}', ?)
			""", id, createdAt);
	}
}
