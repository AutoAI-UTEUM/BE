package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class GoogleOAuthMigrationContractTest {

	@Test
	void v31AddsBackwardCompatibleGoogleIdentityColumns() throws Exception {
		String migration;
		try (var input = getClass().getResourceAsStream(
			"/db/migration/V31__users_google_oauth.sql"
		)) {
			assertThat(input).isNotNull();
			migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
			.contains("auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL'")
			.contains("google_sub VARCHAR(64) NULL")
			.contains("CONSTRAINT uk_users_google_sub UNIQUE (google_sub)");
	}
}
