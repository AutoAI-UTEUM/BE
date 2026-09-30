package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class MaterialCaptionFailureMigrationContractTest {

	@Test
	void v52StoresOnlyNullablePermanentCaptionReasonWithoutChangingExtractionMetadata() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V52__material_caption_failure_reason.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(sql).contains("caption_failure_reason VARCHAR(40) NULL")
				.contains("ADD CONSTRAINT chk_learning_materials_caption_failure_reason")
				.contains("caption_failure_reason IS NULL")
				.doesNotContain("UPDATE learning_materials", "DROP", "DEFAULT", "processing_status");
			assertThat(Pattern.compile("'([A-Z_]+)'").matcher(sql).results()
				.map(match -> match.group(1)).toList())
				.containsExactlyInAnyOrderElementsOf(Arrays.stream(CaptionFailureReason.values())
					.map(Enum::name).toList());
		}
	}
}
