package io.edupilot.admin.dto;

import io.edupilot.user.UserStatus;

public record AdminAiUsageUserResponse(
	Long userId,
	String email,
	String name,
	UserStatus status,
	long callCount,
	Long inputTokens,
	Long outputTokens,
	Long reasoningTokens
) {
}
