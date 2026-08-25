package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClassroomWeeksMigrationContractTest {

	@Test
	void v14DefinesWeeksAndMaterialLinksWithRequiredConstraints() throws IOException {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V14__classroom_weeks_materials.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql)
				.contains("CREATE TABLE classroom_weeks")
				.contains("CREATE TABLE classroom_week_materials")
				.contains("classroom_id BIGINT NOT NULL")
				.contains("week_number INT NOT NULL")
				.contains("release_at DATETIME(6) NULL")
				.contains("UNIQUE (classroom_id, week_number)")
				.contains("CHECK (week_number >= 1)")
				.contains("UNIQUE (week_id, material_id)")
				.contains("INDEX idx_classroom_week_materials_material (material_id)");
		}
	}
}
