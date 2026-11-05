package io.edupilot.auth;

/** Revocation-only dependency for trusted withdrawal/recovery paths. */
@FunctionalInterface
public interface TokenRevoker {
	void revokeAll(Long userId);
}
