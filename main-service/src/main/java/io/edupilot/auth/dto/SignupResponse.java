package io.edupilot.auth.dto;

import io.edupilot.user.User;
import io.edupilot.user.UserRole;

public record SignupResponse(
	Long userId,
	String email,
	String name,
	UserRole role,
	String affiliation,
	String avatarUrl,
	boolean learningEmailOptIn
) {
	public static SignupResponse from(User user) {
		return new SignupResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole(),
			user.getAffiliation(),
			user.getAvatarUrl(),
			user.isLearningEmailOptIn()
		);
	}
}
