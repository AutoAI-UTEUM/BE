package io.edupilot.auth;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.AttemptWindow;

@Component
public class LoginAttemptLimiter {
	private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private final AttemptWindow<String> accountFailures;
	private final AttemptWindow<String> ipFailures;
	private final AttemptWindow<String> refreshFailures;

	public LoginAttemptLimiter() {
		this(Ticker.systemTicker());
	}

	LoginAttemptLimiter(Ticker ticker) {
		accountFailures = new AttemptWindow<>(WINDOW, 100_000, ticker);
		ipFailures = new AttemptWindow<>(WINDOW, 10_000, ticker);
		refreshFailures = new AttemptWindow<>(WINDOW, 10_000, ticker);
	}

	public void checkLogin(String email, String ip) {
		boolean accountBlocked = accountFailures.isBlocked(email, 5);
		boolean ipBlocked = ipFailures.isBlocked(ip, 20);
		if (accountBlocked || ipBlocked) {
			log.atWarn()
				.addKeyValue("action", "LOGIN_RATE_LIMITED")
				.addKeyValue("emailMasked", mask(email))
				.addKeyValue("ip", ip)
				.log("Login attempt rate limited");
			throw new LoginRateLimitedException(
				ErrorCode.LOGIN_RATE_LIMITED,
				Math.max(
					accountBlocked ? accountFailures.retryAfterSeconds(email) : 0,
					ipBlocked ? ipFailures.retryAfterSeconds(ip) : 0
				)
			);
		}
	}

	public void recordLoginFailure(String email, String ip) {
		accountFailures.increment(email);
		ipFailures.increment(ip);
	}

	public void recordLoginSuccess(String email) {
		accountFailures.reset(email);
	}

	public void checkRefresh(String ip) {
		if (refreshFailures.isBlocked(ip, 5)) {
			throw new LoginRateLimitedException(
				ErrorCode.RATE_LIMIT_EXCEEDED,
				refreshFailures.retryAfterSeconds(ip)
			);
		}
	}

	public void recordRefreshFailure(String ip) {
		refreshFailures.increment(ip);
	}

	static String mask(String email) {
		int at = email.indexOf('@');
		return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
	}
}
