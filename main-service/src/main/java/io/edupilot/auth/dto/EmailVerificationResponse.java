package io.edupilot.auth.dto;

import java.time.Instant;
import io.edupilot.user.EmailVerificationState;
import io.edupilot.user.User;

public record EmailVerificationResponse(EmailVerificationState emailVerification, boolean emailVerificationRequired,
	Instant emailVerifiedAt) {
	public static EmailVerificationResponse from(User user) {
		return new EmailVerificationResponse(user.getEmailVerificationState(), !user.isEmailVerified(), user.getEmailVerifiedAt());
	}
}
