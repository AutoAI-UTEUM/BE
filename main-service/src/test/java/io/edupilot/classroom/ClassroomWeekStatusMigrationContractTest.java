package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClassroomWeekStatusMigrationContractTest {

	@Test
	void v21BackfillsStatusAndDisplayOrderWithoutChangingVisibility() throws Exception {
		String migration = migration("V21__classroom_week_status_order.sql");

		assertThat(migration)
			.contains("ADD COLUMN status VARCHAR(20) NULL")
			.contains("ADD COLUMN display_order INT NULL")
			.contains("release_at IS NULL OR release_at <= UTC_TIMESTAMP(6)")
			.contains("THEN 'PUBLISHED'")
			.contains("ELSE 'SCHEDULED'")
			.contains("display_order = week_number")
			.contains("MODIFY COLUMN status VARCHAR(20) NOT NULL")
			.contains("MODIFY COLUMN display_order INT NOT NULL")
			.contains("CHECK (status IN ('PRIVATE', 'SCHEDULED', 'PUBLISHED', 'BREAK'))")
			.contains("CHECK (display_order >= 1)")
			.contains("INDEX idx_classroom_weeks_classroom_display_order")
			.doesNotContain("THEN 'PRIVATE'");
	}

	@Test
	void preservesV20ChecksumAndAppendsV21InOrder() throws Exception {
		assertThat(sha256(migration("V20__user_schedules.sql")))
			.isEqualTo("68db6b2bac341dab59f2ba8436f432facb20de8c759eb0888ab116f43c81c562");
		assertThat(List.of(
			"V20__user_schedules.sql",
			"V21__classroom_week_status_order.sql"
		)).isSortedAccordingTo((left, right) ->
			Integer.compare(version(left), version(right))
		);
	}

	private String migration(String filename) throws Exception {
		try (var input = getClass().getResourceAsStream("/db/migration/" + filename)) {
			assertThat(input).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String sha256(String content) throws Exception {
		byte[] bytes = content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
		return java.util.HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(bytes)
		);
	}

	private int version(String filename) {
		return Integer.parseInt(filename.substring(1, filename.indexOf("__")));
	}
}
