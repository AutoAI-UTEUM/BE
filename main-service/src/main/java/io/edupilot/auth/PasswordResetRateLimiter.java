package io.edupilot.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

@Component
public class PasswordResetRateLimiter {

	private final Cache<String, Integer> requestsByEmail;
	private final Cache<String, Integer> requestsByIp;
	private final Cache<String, Integer> confirmsByIp;

	public PasswordResetRateLimiter() {
		this(Ticker.systemTicker());
	}

	PasswordResetRateLimiter(Ticker ticker) {
		requestsByEmail = Caffeine.newBuilder()
			.maximumSize(100_000).expireAfterWrite(Duration.ofHours(1)).ticker(ticker).build();
		requestsByIp = Caffeine.newBuilder()
			.maximumSize(10_000).expireAfterWrite(Duration.ofHours(1)).ticker(ticker).build();
		confirmsByIp = Caffeine.newBuilder()
			.maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(15))
			.ticker(ticker).build();
	}

	public boolean allowRequest(String email, String ip) {
		boolean emailAllowed = increment(requestsByEmail, email, 3);
		boolean ipAllowed = increment(requestsByIp, ip, 10);
		return emailAllowed && ipAllowed;
	}

	public boolean allowConfirm(String ip) {
		return increment(confirmsByIp, ip, 10);
	}

	private boolean increment(Cache<String, Integer> cache, String key, int limit) {
		return cache.asMap().merge(key, 1, Integer::sum) <= limit;
	}
}
