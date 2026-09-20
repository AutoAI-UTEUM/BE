package io.edupilot.admin.xai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class XaiSyncRateLimiter {

	private final Cache<Long, Boolean> recentRequests;

	@Autowired
	public XaiSyncRateLimiter(XaiManagementProperties properties) {
		this(properties, Ticker.systemTicker());
	}

	XaiSyncRateLimiter(
		XaiManagementProperties properties,
		Ticker ticker
	) {
		this.recentRequests = Caffeine.newBuilder()
			.expireAfterWrite(properties.syncRateLimit())
			.ticker(ticker)
			.build();
	}

	public void acquire(Long actorUserId) {
		if (recentRequests.asMap().putIfAbsent(actorUserId, true) != null) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
	}
}
