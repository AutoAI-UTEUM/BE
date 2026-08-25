package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialXaiFileMigrationContractTest {

	@Test
	void v33AddsNullableXaiFileIdWithoutIndexOrBackfill() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V33__material_xai_file.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("ALTER TABLE learning_materials")
				.contains("ADD COLUMN xai_file_id VARCHAR(255) NULL")
				.doesNotContainIgnoringCase("CREATE INDEX")
				.doesNotContainIgnoringCase("UPDATE ");
		}
	}
}
