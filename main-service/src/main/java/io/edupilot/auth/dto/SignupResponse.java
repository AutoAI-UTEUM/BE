package io.edupilot.auth.dto;

import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import io.edupilot.user.EmailVerificationState;

public record SignupResponse(
	Long userId,
	String email,
	String name,
	UserRole role,
	String affiliation,
	String avatarUrl,
	boolean learningEmailOptIn,
	EmailVerificationState emailVerification,
	@io.swagger.v3.oas.annotations.media.Schema(description = "New signups require email verification; the migration-defined legacy cohort retains its separate access exception")
	boolean emailVerificationRequired
) {
	public static SignupResponse from(User user) {
		return new SignupResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole(),
			user.getAffiliation(),
			user.getAvatarUrl(),
			user.isLearningEmailOptIn(),
			user.getEmailVerificationState(),
			user.isEmailVerificationRequired()
		);
	}
}
