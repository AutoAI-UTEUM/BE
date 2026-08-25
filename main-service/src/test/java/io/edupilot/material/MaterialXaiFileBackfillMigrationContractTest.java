package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialXaiFileBackfillMigrationContractTest {

	@Test
	void v34AddsAttemptTimestampAndCandidateIndexWithoutChangingReadyRows()
		throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V34__material_xai_file_backfill.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("ADD COLUMN xai_file_upload_attempted_at DATETIME(6) NULL")
				.contains("ADD INDEX idx_material_xai_file_backfill")
				.doesNotContainIgnoringCase("UPDATE ");
		}
	}
}
