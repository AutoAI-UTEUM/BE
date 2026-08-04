package io.edupilot.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;

class UserScheduleMigrationContractTest {

	@Test
	void v20ContainsPersonalSchedulePersistenceContract() throws Exception {
		String migration = migration("V20__user_schedules.sql");

		assertThat(migration)
			.contains("CREATE TABLE user_schedules")
			.contains("user_id BIGINT NOT NULL")
			.contains("title VARCHAR(200) NOT NULL")
			.contains("starts_at DATETIME(6) NOT NULL")
			.contains("ends_at DATETIME(6) NOT NULL")
			.contains("has_time BOOLEAN NOT NULL")
			.contains("FOREIGN KEY (user_id) REFERENCES users (id)")
			.contains("CHECK (ends_at >= starts_at)")
			.contains("INDEX idx_user_schedules_user_starts_at (user_id, starts_at)")
			.doesNotContain("kind")
			.doesNotContain("ON DELETE CASCADE");
	}

	@Test
	void preservesV19ChecksumAndAppendsV20InOrder() throws Exception {
		assertThat(sha256(migration("V19__report.sql")))
			.isEqualTo("d7e0e1304f23e952d805b3cce51070f809e0415652ab033560af3ada41114024");
		assertThat(List.of(
			"V19__report.sql",
			"V20__user_schedules.sql"
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
