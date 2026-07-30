package io.edupilot.auth.dto;

import io.edupilot.user.UserRole;

public record SignupResponse(
	Long userId,
	String email,
	String name,
	UserRole role
) {
}
