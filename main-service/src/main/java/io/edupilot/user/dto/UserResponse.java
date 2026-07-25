package io.edupilot.user.dto;

import io.edupilot.user.User;
import io.edupilot.user.UserRole;

public record UserResponse(
	Long id,
	String email,
	String name,
	UserRole role
) {
	public static UserResponse from(User user) {
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole()
		);
	}
}
