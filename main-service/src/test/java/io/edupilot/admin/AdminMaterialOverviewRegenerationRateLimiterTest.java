package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class AdminMaterialOverviewRegenerationRateLimiterTest {

	@Test
	void limitsPerMaterialForOneMinute() {
		AtomicLong nanos = new AtomicLong();
		AdminMaterialOverviewRegenerationRateLimiter limiter =
			new AdminMaterialOverviewRegenerationRateLimiter(nanos::get);

		limiter.acquire(10L);
		assertThatThrownBy(() -> limiter.acquire(10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				org.assertj.core.api.Assertions.assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));
		assertThatCode(() -> limiter.acquire(11L)).doesNotThrowAnyException();
		nanos.addAndGet(Duration.ofMinutes(1).toNanos());
		assertThatCode(() -> limiter.acquire(10L)).doesNotThrowAnyException();
	}
}
