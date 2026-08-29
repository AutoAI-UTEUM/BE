package io.edupilot.admin.dto;

import java.time.Instant;

import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

public record AdminUserResponse(
	Long id,
	String email,
	String name,
	UserRole role,
	UserStatus status,
	AuthProvider authProvider,
	Instant createdAt
) {
	public static AdminUserResponse from(User user) {
		return new AdminUserResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole(),
			user.getStatus(),
			user.getAuthProvider(),
			user.getCreatedAt()
		);
	}
}
