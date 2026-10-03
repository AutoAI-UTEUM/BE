package io.edupilot;

import java.time.Instant;
import io.edupilot.user.User;

/** Existing synthetic actors in tests of other features, with explicit email-confirmation evidence. */
public final class VerifiedTestUsers {
	private VerifiedTestUsers() { }
	public static User verified(User user) {
		user.verifyEmail(Instant.parse("2020-01-01T00:00:00Z"));
		return user;
	}
}
