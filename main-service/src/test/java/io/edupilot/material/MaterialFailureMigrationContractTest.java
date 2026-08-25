package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialFailureMigrationContractTest {

	@Test
	void v23AddsNullableStructuredFailureMetadataWithoutBackfill() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V23__material_failure_metadata.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("failure_reason VARCHAR(40) NULL")
				.contains("failure_trace_id VARCHAR(64) NULL")
				.contains("'EXTRACTION_FAILED'")
				.contains("'PAGE_LIMIT_EXCEEDED'")
				.contains("'SCHEDULING_FAILED'")
				.contains("processing_status = 'FAILED'")
				.doesNotContain("UPDATE learning_materials")
				.doesNotContain("DEFAULT");
		}
	}

	@Test
	void v27ExpandsStructuredFailureReasonCheckWithoutBackfill() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V27__material_failure_reason_expansion.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("DROP CHECK chk_learning_materials_failure_reason")
				.contains("ADD CONSTRAINT chk_learning_materials_failure_reason")
				.contains("'EXTRACTION_FAILED'")
				.contains("'PAGE_LIMIT_EXCEEDED'")
				.contains("'SCHEDULING_FAILED'")
				.contains("'UNSUPPORTED_FORMAT'")
				.contains("'ENCRYPTED_PDF'")
				.contains("'NO_TEXT_CONTENT'")
				.contains("'FILE_TOO_LARGE'")
				.doesNotContain("chk_learning_materials_failure_metadata")
				.doesNotContain("UPDATE learning_materials");
		}
	}
}
