package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MaterialOverviewMigrationContractTest {

	@Test
	void v28CreatesOneOverviewPerMaterialWithStatusCheck() throws Exception {
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V28__material_overviews.sql"
		)) {
			assertThat(input).isNotNull();
			String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

			assertThat(sql)
				.contains("CREATE TABLE material_overviews")
				.contains("material_id BIGINT NOT NULL")
				.contains("content MEDIUMTEXT NULL")
				.contains("status VARCHAR(20) NOT NULL")
				.contains("created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
				.contains("updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
				.contains("ON UPDATE CURRENT_TIMESTAMP(6)")
				.contains("CONSTRAINT uk_material_overviews_material UNIQUE (material_id)")
				.contains("FOREIGN KEY (material_id) REFERENCES learning_materials (id)")
				.contains("CHECK (status IN ('PENDING', 'READY', 'FAILED'))");
		}
	}
}
