package io.edupilot.auth.dto;

import io.edupilot.user.dto.UserResponse;

public record LoginResponse(
	String accessToken,
	String tokenType,
	long expiresIn,
	UserResponse user
) {
}
