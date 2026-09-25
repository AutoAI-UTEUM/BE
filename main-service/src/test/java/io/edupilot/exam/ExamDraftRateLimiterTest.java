package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ExamDraftRateLimiterTest {

	@Test
	void limitsThirtyRequestsPerUserAndExamAndResetsAfterOneMinute() {
		AtomicLong now = new AtomicLong();
		ExamDraftRateLimiter limiter = new ExamDraftRateLimiter(now::get);

		for (int request = 0; request < 30; request++) {
			assertThat(limiter.allow(1L, 10L)).isTrue();
		}
		assertThat(limiter.allow(1L, 10L)).isFalse();
		assertThat(limiter.allow(2L, 10L)).isTrue();
		assertThat(limiter.allow(1L, 11L)).isTrue();

		now.set(Duration.ofMinutes(1).toNanos());
		assertThat(limiter.allow(1L, 10L)).isTrue();
	}
}
