package io.edupilot.auth.dto;

import io.edupilot.user.UserRole;

public enum SignupRole {
	LEARNER,
	INSTRUCTOR;

	public UserRole toUserRole() {
		return UserRole.valueOf(name());
	}
}
