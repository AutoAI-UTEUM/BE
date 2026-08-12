package io.edupilot.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NotificationMigrationContractTest {

	@Test
	void v26AddsNotificationTableAndBackfillsPublishedNoticeMarker() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V26__in_app_notifications.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("CREATE TABLE notifications")
				.contains("user_id BIGINT NOT NULL")
				.contains("link_json JSON NOT NULL")
				.contains("read_at DATETIME(6) NULL")
				.contains("INDEX idx_notifications_user_created (user_id, created_at)")
				.contains("notification_sent_at DATETIME(6) NULL")
				.contains("publish_at IS NULL OR publish_at <= UTC_TIMESTAMP(6)")
				.contains("'MATERIAL_UPLOADED'")
				.contains("'NOTICE_PUBLISHED'")
				.contains("'JOIN_REQUEST_RECEIVED'")
				.contains("'JOIN_REQUEST_PROCESSED'");
		}
	}
}
