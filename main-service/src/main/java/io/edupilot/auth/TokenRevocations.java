package io.edupilot.auth;

import java.time.Instant;

/** Shared database-only revocation; does not issue tokens or require JWT secrets. */
public final class TokenRevocations {
	private TokenRevocations() { }
	public static void revoke(AuthSessionRepository sessions, RefreshTokenRepository tokens, Long userId, Instant now) {
		sessions.revokeAllActiveByUserId(userId, now);
		tokens.revokeAllActiveByUserId(userId, now);
	}
}
