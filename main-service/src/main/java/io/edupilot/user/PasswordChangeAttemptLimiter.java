package io.edupilot.user;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.AttemptWindow;

@Component
public class PasswordChangeAttemptLimiter {

	private static final int MAX_FAILURES = 5;
	private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

	private final AttemptWindow<Long> failures;

	public PasswordChangeAttemptLimiter() {
		this(System::nanoTime);
	}

	PasswordChangeAttemptLimiter(Ticker ticker) {
		this.failures = new AttemptWindow<>(FAILURE_WINDOW, 100_000, ticker);
	}

	public void checkAllowed(Long userId) {
		if (failures.isBlocked(userId, MAX_FAILURES)) {
			throw new BusinessException(ErrorCode.PASSWORD_CHANGE_RATE_LIMITED);
		}
	}

	public void recordFailure(Long userId) {
		failures.increment(userId);
	}

	public void reset(Long userId) {
		failures.reset(userId);
	}
}
