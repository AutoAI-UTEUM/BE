package io.edupilot.auth.dto;

public record SignupResponse(
	Long userId,
	String email,
	String name
) {
}
