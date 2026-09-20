package io.edupilot.auth.dto;

import java.time.Duration;
import java.time.Instant;

import io.edupilot.auth.AuthSession;

public record AuthSessionResponse(
	long idleTimeoutSeconds,
	Instant idleExpiresAt,
	Instant absoluteExpiresAt
) {

	public static AuthSessionResponse from(AuthSession session, Duration idleTtl) {
		return new AuthSessionResponse(
			idleTtl.toSeconds(),
			session.getIdleExpiresAt(),
			session.getAbsoluteExpiresAt()
		);
	}
}
