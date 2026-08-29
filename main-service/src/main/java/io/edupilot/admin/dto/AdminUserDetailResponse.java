package io.edupilot.admin.dto;

import java.time.Instant;

import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

public record AdminUserDetailResponse(
	Long id,
	String email,
	String name,
	UserRole role,
	UserStatus status,
	AuthProvider authProvider,
	Instant createdAt,
	String affiliation,
	Instant consentedAt
) {
	public static AdminUserDetailResponse from(User user) {
		return new AdminUserDetailResponse(
			user.getId(),
			user.getEmail(),
			user.getName(),
			user.getRole(),
			user.getStatus(),
			user.getAuthProvider(),
			user.getCreatedAt(),
			user.getAffiliation(),
			user.getConsentedAt()
		);
	}
}
