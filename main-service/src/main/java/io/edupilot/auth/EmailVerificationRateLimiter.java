package io.edupilot.auth;

import org.springframework.stereotype.Component;

/** Independent mail/confirm windows; password-reset limits remain unchanged. */
@Component
public class EmailVerificationRateLimiter {
	private final PasswordResetRateLimiter windows = new PasswordResetRateLimiter();
	public boolean allowRequest(Long userId, String ip) { return windows.allowRequest(userId.toString(), ip); }
	public boolean allowConfirm(String ip) { return windows.allowConfirm(ip); }
}
