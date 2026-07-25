package io.edupilot.auth;

import io.edupilot.user.UserRole;

public record AuthenticatedUser(
	Long userId,
	UserRole role
) {
}
