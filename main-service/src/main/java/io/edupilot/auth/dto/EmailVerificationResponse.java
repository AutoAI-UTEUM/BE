package io.edupilot.auth.dto;

import java.time.Instant;
import io.edupilot.user.EmailVerificationState;
import io.edupilot.user.User;

public record EmailVerificationResponse(EmailVerificationState emailVerification,
	@io.swagger.v3.oas.annotations.media.Schema(description = "Whether access requires email verification; a legacy policy exception is not verification evidence")
	boolean emailVerificationRequired,
	Instant emailVerifiedAt) {
	public static EmailVerificationResponse from(User user) {
		return new EmailVerificationResponse(user.getEmailVerificationState(), user.isEmailVerificationRequired(), user.getEmailVerifiedAt());
	}
}
