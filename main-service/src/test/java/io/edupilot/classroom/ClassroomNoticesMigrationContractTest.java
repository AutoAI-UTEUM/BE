package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClassroomNoticesMigrationContractTest {

	@Test
	void v15DefinesImmediateNoticesWithoutSchedulingOrReadCount() throws IOException {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V15__classroom_notices.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql)
				.contains("CREATE TABLE classroom_notices")
				.contains("classroom_id BIGINT NOT NULL")
				.contains("title VARCHAR(200) NOT NULL")
				.contains("content TEXT NOT NULL")
				.contains("published_at DATETIME(6) NOT NULL")
				.contains("INDEX idx_classroom_notices_classroom_published")
				.doesNotContain("scheduled_at")
				.doesNotContain("read_count");
		}
	}

	@Test
	void v22AddsNullableWeekAndPublishAtWithoutRewritingExistingNotices()
		throws IOException {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V22__classroom_notice_week_publish.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql)
				.contains("ADD COLUMN week_number INT NULL")
				.contains("ADD COLUMN publish_at DATETIME(6) NULL")
				.contains("CHECK (week_number IS NULL OR week_number >= 1)")
				.doesNotContain("UPDATE classroom_notices")
				.doesNotContain("DEFAULT");
		}
	}
}
