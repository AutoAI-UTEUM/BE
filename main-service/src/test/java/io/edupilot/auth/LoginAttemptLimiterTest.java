package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.ErrorCode;

class LoginAttemptLimiterTest {
	private final AtomicLong nanos = new AtomicLong();
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(nanos::get);

	@Test
	void fifthAccountFailureBlocksNextAttemptUntilFifteenMinutes() {
		for (int attempt = 0; attempt < 5; attempt++) {
			limiter.checkLogin("user@example.com", "192.0.2.1");
			limiter.recordLoginFailure("user@example.com", "192.0.2.1");
		}
		assertThatThrownBy(() -> limiter.checkLogin("user@example.com", "192.0.2.2"))
			.isInstanceOfSatisfying(LoginRateLimitedException.class, exception -> {
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.LOGIN_RATE_LIMITED);
				assertThat(exception.retryAfterSeconds()).isEqualTo(900);
			});
		nanos.addAndGet(Duration.ofMinutes(15).toNanos());
		limiter.checkLogin("user@example.com", "192.0.2.2");
	}

	@Test
	void successClearsAccountButNotIpAndUnknownAccountsCountEqually() {
		for (int attempt = 0; attempt < 20; attempt++) {
			String email = "missing-" + attempt + "@example.com";
			limiter.recordLoginFailure(email, "192.0.2.1");
		}
		assertThatThrownBy(() -> limiter.checkLogin("known@example.com", "192.0.2.1"))
			.isInstanceOf(LoginRateLimitedException.class);
		limiter.recordLoginSuccess("missing-0@example.com");
		limiter.checkLogin("missing-0@example.com", "192.0.2.2");
	}

	@Test
	void successfulLoginClearsOnlyTheAccountFailureWindow() {
		for (int attempt = 0; attempt < 4; attempt++) {
			limiter.recordLoginFailure("user@example.com", "192.0.2.1");
		}
		limiter.recordLoginSuccess("user@example.com");
		for (int attempt = 0; attempt < 4; attempt++) {
			limiter.recordLoginFailure("user@example.com", "192.0.2.1");
		}
		limiter.checkLogin("user@example.com", "192.0.2.1");
		limiter.recordLoginFailure("user@example.com", "192.0.2.1");
		assertThatThrownBy(() -> limiter.checkLogin("user@example.com", "192.0.2.2"))
			.isInstanceOf(LoginRateLimitedException.class);
	}

	@Test
	void refreshOnlyCountsFailuresAndSixthAttemptIsLimited() {
		for (int attempt = 0; attempt < 5; attempt++) {
			limiter.checkRefresh("192.0.2.1");
			limiter.recordRefreshFailure("192.0.2.1");
		}
		assertThatThrownBy(() -> limiter.checkRefresh("192.0.2.1"))
			.isInstanceOfSatisfying(LoginRateLimitedException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)
			);
		limiter.checkRefresh("192.0.2.2");
	}

	@Test
	void masksEmailLocalPart() {
		assertThat(LoginAttemptLimiter.mask("known@example.com"))
			.isEqualTo("k***@example.com");
	}
}
