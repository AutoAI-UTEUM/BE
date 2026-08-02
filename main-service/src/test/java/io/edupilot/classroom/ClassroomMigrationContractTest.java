package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClassroomMigrationContractTest {

	@Test
	void v13ContainsClassroomCoreContract() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V13__classroom_core.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("CREATE TABLE classrooms")
			.contains("CREATE TABLE classroom_members")
			.contains("CREATE TABLE classroom_join_requests")
			.contains("uk_classrooms_invite_code")
			.contains("uk_classroom_members_classroom_user")
			.contains("uk_classroom_join_requests_classroom_user")
			.contains("CHECK (end_date >= start_date)")
			.contains("'BLUE', 'GREEN', 'PURPLE', 'ORANGE', 'RED', 'GRAY'")
			.contains("'PENDING', 'APPROVED', 'REJECTED'")
			.contains("created_at DATETIME(6) NOT NULL")
			.contains("updated_at DATETIME(6) NOT NULL");
	}
}
