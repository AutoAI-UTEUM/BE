package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialPageCaptionMigrationContractTest {

	@Test
	void v30AddsNullableCaptionAndCompletionTimestampWithoutBackfill() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V30__material_page_captions.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("ALTER TABLE material_pages")
				.contains("ADD COLUMN caption TEXT NULL AFTER text_content")
				.contains("ALTER TABLE learning_materials")
				.contains("ADD COLUMN captions_completed_at DATETIME(6) NULL")
				.doesNotContainIgnoringCase("UPDATE ");
		}
	}
}
