package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class XaiSyncRateLimiterTest {

	@Test
	void allowsOneSyncPerActorPerMinute() {
		MutableTicker ticker = new MutableTicker();
		XaiSyncRateLimiter limiter = new XaiSyncRateLimiter(properties(), ticker);

		limiter.acquire(1L);
		assertThatThrownBy(() -> limiter.acquire(1L))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)
			);
		assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException();

		ticker.advance(Duration.ofMinutes(1).plusNanos(1));
		assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
	}

	private XaiManagementProperties properties() {
		return new XaiManagementProperties(
			"https://management-api.x.ai",
			"secret",
			"team",
			Duration.ofSeconds(2),
			Duration.ofSeconds(5),
			Duration.ofMinutes(2),
			Duration.ofMinutes(2),
			Duration.ofMinutes(5),
			Duration.ofMinutes(1)
		);
	}

	private static final class MutableTicker implements Ticker {

		private final AtomicLong nanos = new AtomicLong();

		@Override
		public long read() {
			return nanos.get();
		}

		private void advance(Duration duration) {
			nanos.addAndGet(duration.toNanos());
		}
	}
}
