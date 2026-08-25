package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClassroomResourceMigrationContractTest {

	@Test
	void v32DefinesFileAndLinkMetadataWithClassroomWeekIndex()
		throws IOException {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V32__classroom_resources.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql)
				.contains("CREATE TABLE classroom_resource")
				.contains("classroom_id BIGINT NOT NULL")
				.contains("type VARCHAR(10) NOT NULL")
				.contains("title VARCHAR(200) NOT NULL")
				.contains("week_number INT NULL")
				.contains("file_name VARCHAR(255) NULL")
				.contains("url VARCHAR(2048) NULL")
				.contains("CHECK (type IN ('FILE', 'LINK'))")
				.contains("CONSTRAINT chk_classroom_resource_metadata")
				.contains("INDEX idx_classroom_resource_classroom_week_created")
				.doesNotContain("ON DELETE CASCADE");
		}
	}
}
