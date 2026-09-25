package io.edupilot.auth.dto;

import java.util.List;

import io.edupilot.policy.dto.PendingPolicyVersion;
import io.edupilot.user.dto.UserResponse;

public record LoginResponse(
	String accessToken,
	String tokenType,
	long expiresIn,
	UserResponse user,
	AuthSessionResponse session,
	List<PendingPolicyVersion> pendingConsents
) {
}
