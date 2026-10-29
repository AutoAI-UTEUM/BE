package io.edupilot.guardian;

import java.time.Instant;

/** Confirms control of a phone only. Implementations must not claim legal guardian relationship evidence. */
public interface GuardianPhoneProvider {
	boolean connected();
	Receipt send(String phone, String idempotencyKey, Instant expiresAt);
	Result verify(String providerReference, String code, String idempotencyKey, Instant expiresAt);
	record Receipt(String reference) {
		@Override public String toString() { return "GuardianPhoneReceipt[REDACTED]"; }
	}
	enum Result { PHONE_CONFIRMED, MISMATCH }

	final class Failure extends RuntimeException {
		private final Category category;
		public Failure(Category category) { super("Guardian phone provider " + category); this.category = category; }
		public Category category() { return category; }
	}
	enum Category { UNAVAILABLE, REJECTED, RESULT_UNKNOWN }
}
