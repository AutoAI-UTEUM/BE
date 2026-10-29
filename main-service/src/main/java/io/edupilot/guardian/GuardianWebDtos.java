package io.edupilot.guardian;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

public final class GuardianWebDtos {
	private GuardianWebDtos() { }
	public record Link(String url, Instant expiresAt) {
		@Override public String toString() { return "GuardianLink[REDACTED]"; }
	}
	public record Token(@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token) {
		@Override public String toString() { return "GuardianToken[REDACTED]"; }
	}
	public record Consent(@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token,
		String noticeVersion, boolean accepted, boolean declaresLegalGuardian,
		@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String phone) {
		@Override public String toString() { return "GuardianConsent[REDACTED]"; }
	}
	public record Code(@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token,
		@Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String code) {
		@Override public String toString() { return "GuardianCode[REDACTED]"; }
	}
	public record Status(GuardianWebRequest.State state, String noticeVersion, String noticeDigest, String noticeUrl,
		Instant expiresAt, boolean consentRecorded, boolean phoneControlConfirmed,
		@Schema(description = "Phone control and self-declaration do not establish legal guardian relationship evidence")
		boolean guardianRelationshipVerified, GuardianWebRequest.Reason exceptionReason) { }
}
