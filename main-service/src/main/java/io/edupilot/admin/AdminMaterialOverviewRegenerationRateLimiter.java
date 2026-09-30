package io.edupilot.admin;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class AdminMaterialOverviewRegenerationRateLimiter {

	private final Cache<Long, Boolean> recent;

	public AdminMaterialOverviewRegenerationRateLimiter() {
		this(Ticker.systemTicker());
	}

	AdminMaterialOverviewRegenerationRateLimiter(Ticker ticker) {
		this.recent = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterWrite(Duration.ofMinutes(1))
			.ticker(ticker)
			.build();
	}

	public void acquire(Long materialId) {
		if (recent.asMap().putIfAbsent(materialId, true) != null) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
	}
}
