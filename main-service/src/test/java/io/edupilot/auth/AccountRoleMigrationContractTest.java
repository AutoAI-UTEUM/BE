package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AccountRoleMigrationContractTest {

	@Test
	void v8MigratesLegacyUserRoleAndConstrainsAccountRoles() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V8__account_roles.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("DROP CHECK chk_users_role")
			.contains("SET role = 'LEARNER'")
			.contains("WHERE role = 'USER'")
			.contains("ALTER COLUMN role SET DEFAULT 'LEARNER'")
			.contains("CHECK (role IN ('LEARNER', 'INSTRUCTOR', 'ADMIN'))");
	}
}
