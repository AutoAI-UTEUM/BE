package io.edupilot.auth.dto;

public record AccessTokenResponse(
	String accessToken,
	String tokenType,
	long expiresIn
) {
}
