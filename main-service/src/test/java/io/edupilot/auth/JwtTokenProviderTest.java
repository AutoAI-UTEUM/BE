package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;

class JwtTokenProviderTest {

	private static final String SECRET =
		"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	@Test
	void createsAndParsesMinimalAccessClaims() {
		JwtTokenProvider provider = provider(Clock.systemUTC());
		User user = User.create("user@example.com", "hash", "홍길동");
		ReflectionTestUtils.setField(user, "id", 7L);

		String token = provider.createAccessToken(user);
		AuthenticatedUser principal = provider.parseAccessToken(token);

		assertThat(principal.userId()).isEqualTo(7L);
		assertThat(principal.role()).isEqualTo(user.getRole());
	}

	@Test
	void distinguishesExpiredAndForgedTokens() {
		User user = User.create("user@example.com", "hash", "홍길동");
		ReflectionTestUtils.setField(user, "id", 7L);
		JwtTokenProvider pastProvider = provider(Clock.fixed(
			Instant.parse("2020-01-01T00:00:00Z"),
			ZoneOffset.UTC
		));
		String expired = pastProvider.createAccessToken(user);

		assertTokenError(
			() -> pastProvider.parseAccessToken(expired),
			ErrorCode.TOKEN_EXPIRED
		);

		JwtTokenProvider currentProvider = provider(Clock.systemUTC());
		String valid = currentProvider.createAccessToken(user);
		assertTokenError(
			() -> currentProvider.parseAccessToken(valid + "tampered"),
			ErrorCode.TOKEN_INVALID
		);
	}

	@Test
	void rejectsSecretsShorterThan256Bits() {
		JwtProperties properties = new JwtProperties(
			"c2hvcnQ=",
			Duration.ofHours(1),
			Duration.ofDays(14)
		);

		assertThatThrownBy(() -> new JwtTokenProvider(properties, Clock.systemUTC()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("256 bits");
	}

	private JwtTokenProvider provider(Clock clock) {
		return new JwtTokenProvider(
			new JwtProperties(SECRET, Duration.ofHours(1), Duration.ofDays(14)),
			clock
		);
	}

	private void assertTokenError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(
				JwtTokenValidationException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
