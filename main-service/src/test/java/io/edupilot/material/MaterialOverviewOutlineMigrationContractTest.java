package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialOverviewOutlineMigrationContractTest {

	@Test
	void v29AddsOnlyNullableOutlineJsonColumn() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V29__material_overview_outline.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("ALTER TABLE material_overviews")
				.contains("ADD COLUMN outline_json JSON NULL AFTER content")
				.doesNotContain("UPDATE material_overviews")
				.doesNotContain("DROP CONSTRAINT")
				.doesNotContain("DROP INDEX");
		}
	}
}
