package io.edupilot.guardian.team;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.edupilot.user.User;
import jakarta.persistence.*;

/** One current case per account. Plain tokens and reply bodies are never persisted. */
@Entity
@Table(name = "guardian_team_requests")
public class GuardianTeamRequest {
	@Id @Column(length = 36) private String id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
	@Column(nullable = false) private long generation;
	@Column(nullable = false) private long revision;
	@Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) private State state;
	@Column(name = "notice_version", length = 100, nullable = false) private String noticeVersion;
	@Column(name = "notice_digest", length = 64, nullable = false) private String noticeDigest;
	@Column(name = "configuration_digest", length = 64, nullable = false) private String configurationDigest;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "generation_started_at", nullable = false) private Instant generationStartedAt;
	@Column(name = "request_expires_at", nullable = false) private Instant requestExpiresAt;
	@Column(name = "token_hash", length = 64, unique = true) private String tokenHash;
	@Column(name = "consumed_token_hash", length = 64) private String consumedTokenHash;
	@Column(name = "token_expires_at") private Instant tokenExpiresAt;
	@Column(name = "guardian_name", length = 100) private String guardianName;
	@Column(name = "guardian_contact", length = 254) private String guardianContact;
	@Enumerated(EnumType.STRING) @Column(name = "contact_origin", length = 20, nullable = false) private ContactOrigin contactOrigin;
	@Column(name = "first_collected_at") private Instant firstCollectedAt;
	@Column(name = "unconfirmed_erase_due_at") private Instant unconfirmedEraseDueAt;
	@Column(name = "web_declared_at") private Instant webDeclaredAt;
	@Enumerated(EnumType.STRING) @Column(length = 25) private Relationship relationship;
	@Column(name = "declared_scopes", length = 100) private String declaredScopes;
	@Column(name = "explicit_response_at") private Instant explicitResponseAt;
	@Enumerated(EnumType.STRING) @Column(name = "confirmation_method", length = 20) private ConfirmationMethod confirmationMethod;
	@Column(name = "evidence_reference", length = 100) private String evidenceReference;
	@Column(name = "confirmed_by") private Long confirmedBy;
	@Column(name = "relationship_checked", nullable = false) private boolean relationshipChecked;
	@Column(name = "legal_method_checked", nullable = false) private boolean legalMethodChecked;
	@Column(name = "approved_at") private Instant approvedAt;
	@Column(name = "approved_until") private Instant approvedUntil;
	@Column(name = "approved_by") private Long approvedBy;
	@Column(name = "evidence_erase_due_at") private Instant evidenceEraseDueAt;
	@Column(name = "ended_at") private Instant endedAt;
	@Enumerated(EnumType.STRING) @Column(name = "reason_code", length = 40) private Reason reasonCode;
	@Column(name = "evidence_erased_at") private Instant evidenceErasedAt;

	protected GuardianTeamRequest() { }

	static GuardianTeamRequest create(User user, GuardianTeamProperties policy, String configurationDigest, Instant now,
		String name, String contact) {
		var row = new GuardianTeamRequest(); row.id = UUID.randomUUID().toString(); row.user = user;
		row.createdAt = now; row.generation = 0; row.revision = 0;
		row.start(policy, configurationDigest, now, name, contact); return row;
	}

	void start(GuardianTeamProperties policy, String configurationDigest, Instant now, String name, String contact) {
		generation++; revision++; state = State.AWAITING_CONSENT;
		noticeVersion = policy.noticeVersion(); noticeDigest = policy.noticeDigest(); this.configurationDigest = configurationDigest;
		generationStartedAt = now; requestExpiresAt = now.plus(policy.requestTtl());
		tokenHash = null; consumedTokenHash = null; tokenExpiresAt = null; guardianName = null; guardianContact = null;
		contactOrigin = ContactOrigin.NONE; firstCollectedAt = null; unconfirmedEraseDueAt = null;
		webDeclaredAt = null; relationship = null; declaredScopes = null; explicitResponseAt = null; confirmationMethod = null;
		evidenceReference = null; confirmedBy = null; relationshipChecked = false; legalMethodChecked = false;
		approvedAt = null; approvedUntil = null; approvedBy = null; evidenceEraseDueAt = null; endedAt = null; reasonCode = null;
		evidenceErasedAt = null;
		collect(name, contact, ContactOrigin.CHILD, now);
	}

	void issueToken(String hash, Instant now, Duration ttl) {
		tokenHash = hash; consumedTokenHash = null;
		Instant desired = now.plus(ttl); tokenExpiresAt = desired.isBefore(requestExpiresAt) ? desired : requestExpiresAt;
		// A new declaration link invalidates earlier intent/confirmation without extending collection deadlines.
		state = State.AWAITING_CONSENT; webDeclaredAt = null; relationship = null; declaredScopes = null;
		explicitResponseAt = null; confirmationMethod = null; evidenceReference = null; confirmedBy = null;
		legalMethodChecked = false; relationshipChecked = false;
		revision++;
	}

	void declare(String name, String contact, Relationship relation, String scopes, Instant now) {
		collect(name, contact, ContactOrigin.GUARDIAN, now);
		webDeclaredAt = now; relationship = relation; declaredScopes = scopes;
		consumedTokenHash = tokenHash; tokenHash = null; state = State.DECLARED; revision++;
	}

	void confirm(ConfirmationMethod method, String reference, Instant receivedAt, Long reviewer,
		Relationship relation, String scopes, Instant now) {
		explicitResponseAt = receivedAt; confirmationMethod = method; evidenceReference = reference;
		confirmedBy = reviewer; relationship = relation; declaredScopes = scopes; legalMethodChecked = true;
		state = State.REVIEW_PENDING; revision++;
		// A manual record is evidence of an explicit response, not a relationship approval.
		if (firstCollectedAt == null) { firstCollectedAt = now; unconfirmedEraseDueAt = now.plus(Duration.ofDays(5)); }
	}

	void approve(Long reviewer, Instant now, Duration validity, Duration retention) {
		relationshipChecked = true; approvedAt = now; approvedBy = reviewer; approvedUntil = now.plus(validity);
		evidenceEraseDueAt = now.plus(retention); state = State.APPROVED; reasonCode = Reason.APPROVED; revision++;
		eraseContactsAndTokens(now);
	}

	void needsInformation(Reason reason) {
		state = State.NEEDS_INFORMATION; reasonCode = reason; explicitResponseAt = null; confirmationMethod = null;
		evidenceReference = null; confirmedBy = null; legalMethodChecked = false; relationshipChecked = false;
		tokenHash = null; consumedTokenHash = null; tokenExpiresAt = null; revision++;
	}

	void end(State target, Reason reason, Instant now) {
		state = target; reasonCode = reason; endedAt = now; revision++; eraseContactsAndTokens(now); eraseEvidence(now);
		approvedAt = null; approvedUntil = null;
	}

	private void collect(String name, String contact, ContactOrigin origin, Instant now) {
		if (name == null && contact == null) { return; }
		if (firstCollectedAt == null) {
			firstCollectedAt = now; unconfirmedEraseDueAt = now.plus(Duration.ofDays(5)); contactOrigin = origin;
		}
		if (name != null) { guardianName = name; }
		if (contact != null) { guardianContact = contact; }
		// The first collection deadline never moves when a link is reissued or a contact is corrected.
	}

	public void eraseContactsAndTokens(Instant now) {
		guardianName = null; guardianContact = null; tokenHash = null; consumedTokenHash = null; tokenExpiresAt = null;
	}
	public void eraseEvidence(Instant now) {
		evidenceReference = null; confirmedBy = null; approvedBy = null; relationship = null; declaredScopes = null;
		webDeclaredAt = null; explicitResponseAt = null; confirmationMethod = null;
		relationshipChecked = false; legalMethodChecked = false; evidenceErasedAt = now;
	}
	boolean pending() { return switch (state) {
		case AWAITING_CONSENT, DECLARED, REVIEW_PENDING, NEEDS_INFORMATION -> true;
		default -> false;
	}; }
	boolean requestExpired(Instant now) { return pending() && !requestExpiresAt.isAfter(now); }
	boolean tokenUsable(String hash, Instant now) {
		return pending() && hash.equals(tokenHash) && tokenExpiresAt != null && tokenExpiresAt.isAfter(now) && requestExpiresAt.isAfter(now);
	}
	boolean evidenceExpired(Instant now) { return evidenceErasedAt == null && evidenceEraseDueAt != null && !evidenceEraseDueAt.isAfter(now); }
	boolean approvedExpired(Instant now) { return state == State.APPROVED && approvedUntil != null && !approvedUntil.isAfter(now); }
	boolean unconfirmedExpired(Instant now) {
		return pending() && unconfirmedEraseDueAt != null && !unconfirmedEraseDueAt.isAfter(now);
	}
	public String id() { return id; }
	public Long userId() { return user.getId(); }
	User user() { return user; }
	public long generation() { return generation; }
	public long revision() { return revision; }
	public State state() { return state; }
	public Instant firstCollectedAt() { return firstCollectedAt; }
	public Instant unconfirmedEraseDueAt() { return unconfirmedEraseDueAt; }
	public Instant evidenceEraseDueAt() { return evidenceEraseDueAt; }
	Instant generationStartedAt() { return generationStartedAt; }
	Instant requestExpiresAt() { return requestExpiresAt; }
	Instant tokenExpiresAt() { return tokenExpiresAt; }
	String consumedTokenHash() { return consumedTokenHash; }
	String noticeVersion() { return noticeVersion; }
	String noticeDigest() { return noticeDigest; }
	String configurationDigest() { return configurationDigest; }
	String guardianName() { return guardianName; }
	String guardianContact() { return guardianContact; }
	ContactOrigin contactOrigin() { return contactOrigin; }
	Instant webDeclaredAt() { return webDeclaredAt; }
	Instant explicitResponseAt() { return explicitResponseAt; }
	ConfirmationMethod confirmationMethod() { return confirmationMethod; }
	String evidenceReference() { return evidenceReference; }
	Relationship relationship() { return relationship; }
	String declaredScopes() { return declaredScopes; }
	Instant approvedUntil() { return approvedUntil; }
	Reason reasonCode() { return reasonCode; }
	boolean legalMethodChecked() { return legalMethodChecked; }

	public enum State { AWAITING_CONSENT, DECLARED, REVIEW_PENDING, NEEDS_INFORMATION, APPROVED, REJECTED, REVOKED, EXPIRED, WITHDRAWN }
	public enum ContactOrigin { NONE, CHILD, GUARDIAN }
	public enum Relationship { PARENT, MINOR_GUARDIAN }
	public enum ConfirmationMethod { EMAIL_REPLY, PHONE }
	public enum Reason { APPROVED, INCOMPLETE_RESPONSE, RELATIONSHIP_UNCONFIRMED, CONSENT_DECLINED, REQUESTER_WITHDREW, OPERATOR_REVOKED, DEADLINE_EXPIRED, ACCOUNT_WITHDRAWN, NOTICE_CHANGED }
}
