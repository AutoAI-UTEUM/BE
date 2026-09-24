package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class PasswordResetRateLimiterTest {

	private final AtomicLong nanos = new AtomicLong();
	private final PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(nanos::get);

	@Test
	void requestLimitsEmailToThreeAndIpToTenPerHour() {
		for (int index = 0; index < 3; index++) {
			assertThat(limiter.allowRequest("one@example.com", "192.0.2.1")).isTrue();
		}
		assertThat(limiter.allowRequest("one@example.com", "192.0.2.1")).isFalse();
		for (int index = 0; index < 6; index++) {
			assertThat(limiter.allowRequest("other-" + index + "@example.com", "192.0.2.1"))
				.isTrue();
		}
		assertThat(limiter.allowRequest("last@example.com", "192.0.2.1")).isFalse();

		nanos.addAndGet(Duration.ofHours(1).plusSeconds(1).toNanos());
		assertThat(limiter.allowRequest("one@example.com", "192.0.2.1")).isTrue();
	}

	@Test
	void confirmRejectsEleventhIpAttemptAndResetsAfterFifteenMinutes() {
		for (int index = 0; index < 10; index++) {
			assertThat(limiter.allowConfirm("192.0.2.2")).isTrue();
		}
		assertThat(limiter.allowConfirm("192.0.2.2")).isFalse();
		nanos.addAndGet(Duration.ofMinutes(15).plusSeconds(1).toNanos());
		assertThat(limiter.allowConfirm("192.0.2.2")).isTrue();
	}
}
