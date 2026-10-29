package io.edupilot.guardian;

import java.time.Instant;
import java.util.UUID;
import io.edupilot.user.User;
import jakarta.persistence.*;

/** Versioned self-declared consent and phone-control evidence; never a guardian relationship approval. */
@Entity
@Table(name = "guardian_web_requests")
public class GuardianWebRequest {
	@Id @Column(length = 36) private String id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
	@Column(name = "token_hash", length = 64, nullable = false, unique = true) private String tokenHash;
	@Column(name = "notice_version", length = 100, nullable = false) private String noticeVersion;
	@Column(name = "notice_digest", length = 64, nullable = false) private String noticeDigest;
	@Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) private State state;
	@Column(name = "issued_at", nullable = false) private Instant issuedAt;
	@Column(name = "expires_at", nullable = false) private Instant expiresAt;
	@Column(name = "consented_at") private Instant consentedAt;
	@Column(name = "legal_guardian_declared", nullable = false) private boolean legalGuardianDeclared;
	@Column(name = "phone_fingerprint", length = 64) private String phoneFingerprint;
	@Column(name = "phone_expires_at") private Instant phoneExpiresAt;
	@Column(name = "provider_reference", length = 100) private String providerReference;
	@Column(name = "attempt_token", length = 36) private String attemptToken;
	@Column(name = "code_attempts", nullable = false) private int codeAttempts;
	@Column(name = "phone_confirmed_at") private Instant phoneConfirmedAt;
	@Enumerated(EnumType.STRING) @Column(name = "exception_reason", length = 30) private Reason exceptionReason;
	protected GuardianWebRequest() { }
	static GuardianWebRequest issue(User user, String hash, GuardianWebProperties policy, Instant now) {
		var request = new GuardianWebRequest(); request.id = UUID.randomUUID().toString(); request.user = user;
		request.tokenHash = hash; request.noticeVersion = policy.noticeVersion(); request.noticeDigest = policy.noticeDigest();
		request.state = State.AWAITING_CONSENT; request.issuedAt = now; request.expiresAt = now.plus(policy.linkTtl());
		return request;
	}
	String consent(String fingerprint, Instant now, Instant deadline) {
		consentedAt = now; legalGuardianDeclared = true; phoneFingerprint = fingerprint; phoneExpiresAt = deadline;
		state = State.SENDING; attemptToken = UUID.randomUUID().toString(); return attemptToken;
	}
	void sent(String reference) { providerReference = reference; attemptToken = null; state = State.PHONE_PENDING; }
	String verifying() { state = State.VERIFYING; codeAttempts++; attemptToken = UUID.randomUUID().toString(); return attemptToken; }
	void mismatch(int limit) {
		attemptToken = null;
		if (codeAttempts >= limit) { review(Reason.CODE_MISMATCH); } else { state = State.PHONE_PENDING; }
	}
	void phoneConfirmed(Instant now) { phoneConfirmedAt = now; state = State.PHONE_CONFIRMED; attemptToken = null; providerReference = null; }
	void review(Reason reason) { state = State.REVIEW_REQUIRED; exceptionReason = reason; attemptToken = null; }
	void cancel() { state = State.CANCELLED; attemptToken = null; providerReference = null; phoneFingerprint = null; }
	boolean usable(Instant now) { return state != State.CANCELLED && expiresAt.isAfter(now); }
	boolean matchesAttempt(String token, State expected) { return state == expected && token.equals(attemptToken); }
	Long userId() { return user.getId(); }
	String id() { return id; } State state() { return state; } Instant expiresAt() { return expiresAt; }
	String tokenHash() { return tokenHash; } String noticeVersion() { return noticeVersion; } String noticeDigest() { return noticeDigest; }
	Instant phoneExpiresAt() { return phoneExpiresAt; } String providerReference() { return providerReference; }
	Instant consentedAt() { return consentedAt; } Instant phoneConfirmedAt() { return phoneConfirmedAt; }
	int codeAttempts() { return codeAttempts; } Reason reason() { return exceptionReason; } Instant issuedAt() { return issuedAt; }
	public enum State { AWAITING_CONSENT, SENDING, PHONE_PENDING, VERIFYING, PHONE_CONFIRMED, REVIEW_REQUIRED, CANCELLED }
	public enum Reason { PROVIDER_UNAVAILABLE, PROVIDER_REJECTED, PROVIDER_RESULT_UNKNOWN, CODE_MISMATCH, EXPIRED, DISPUTED }
}
