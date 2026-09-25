package io.edupilot.usernote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

class NoteImportRateLimiterTest {

	@Test
	void allowsFiveRequestsPerMinuteThenResets() {
		AtomicLong nanos = new AtomicLong();
		Ticker ticker = nanos::get;
		NoteImportRateLimiter limiter = new NoteImportRateLimiter(ticker);
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(limiter.allow(1L)).isTrue();
		}
		assertThat(limiter.allow(1L)).isFalse();
		nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
		assertThat(limiter.allow(1L)).isTrue();
	}
}
