package io.edupilot.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.security.AttemptWindow;

@Component
public class PasswordResetRateLimiter {

	private final AttemptWindow<String> requestsByEmail;
	private final AttemptWindow<String> requestsByIp;
	private final AttemptWindow<String> confirmsByIp;

	public PasswordResetRateLimiter() {
		this(Ticker.systemTicker());
	}

	PasswordResetRateLimiter(Ticker ticker) {
		requestsByEmail = new AttemptWindow<>(Duration.ofHours(1), 100_000, ticker);
		requestsByIp = new AttemptWindow<>(Duration.ofHours(1), 10_000, ticker);
		confirmsByIp = new AttemptWindow<>(Duration.ofMinutes(15), 10_000, ticker);
	}

	public boolean allowRequest(String email, String ip) {
		boolean emailAllowed = requestsByEmail.increment(email) <= 3;
		boolean ipAllowed = requestsByIp.increment(ip) <= 10;
		return emailAllowed && ipAllowed;
	}

	public boolean allowConfirm(String ip) {
		return confirmsByIp.increment(ip) <= 10;
	}
}
