package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AccountProfileMigrationContractTest {

	@Test
	void v10AddsBackwardCompatibleAccountAndPreferenceColumns() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V10__account_profile.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("affiliation VARCHAR(100) NULL")
			.contains("avatar_key VARCHAR(255) NULL")
			.contains("learning_email_opt_in BOOLEAN NOT NULL DEFAULT FALSE")
			.contains("terms_version VARCHAR(50) NULL")
			.contains("privacy_version VARCHAR(50) NULL")
			.contains("consented_at DATETIME(6) NULL")
			.contains("new_material_notification BOOLEAN NOT NULL DEFAULT TRUE")
			.contains("study_reminder BOOLEAN NOT NULL DEFAULT TRUE")
			.contains("ai_answer_style VARCHAR(20) NOT NULL DEFAULT 'NORMAL'")
			.contains("CHECK (ai_answer_style IN ('CONCISE', 'NORMAL', 'DETAILED'))");
	}
}
