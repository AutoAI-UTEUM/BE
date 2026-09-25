package io.edupilot.auth;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class LoginRateLimitedException extends BusinessException {
	private final long retryAfterSeconds;

	public LoginRateLimitedException(ErrorCode errorCode, long retryAfterSeconds) {
		super(errorCode);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
