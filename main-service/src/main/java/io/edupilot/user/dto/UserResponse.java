package io.edupilot.user.dto;

import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import io.edupilot.user.EmailVerificationState;
import java.time.Instant;

public record UserResponse(
	Long id,
	String email,
	String name,
	UserRole role,
	String affiliation,
	String avatarUrl,
	boolean learningEmailOptIn,
	EmailVerificationState emailVerification,
	boolean emailVerificationRequired,
	Instant emailVerifiedAt
) {
	public static UserResponse from(User user) {
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole(),
			user.getAffiliation(),
			user.getAvatarUrl(),
			user.isLearningEmailOptIn(),
			user.getEmailVerificationState(),
			!user.isEmailVerified(),
			user.getEmailVerifiedAt()
		);
	}
}
