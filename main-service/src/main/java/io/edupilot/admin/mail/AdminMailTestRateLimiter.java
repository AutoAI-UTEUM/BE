package io.edupilot.admin.mail;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class AdminMailTestRateLimiter {

	private final Cache<Long, Boolean> recent;

	public AdminMailTestRateLimiter() {
		this(Ticker.systemTicker());
	}

	AdminMailTestRateLimiter(Ticker ticker) {
		this.recent = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofMinutes(1))
			.ticker(ticker)
			.build();
	}

	public void acquire(Long actorUserId) {
		if (recent.asMap().putIfAbsent(actorUserId, true) != null) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
	}
}
