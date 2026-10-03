package io.edupilot.deletion;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "deletion_intents")
public class DeletionIntent {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "key_hash", nullable = false, unique = true, length = 64) private String keyHash;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DeletionKind kind;
	@Column(name = "resource_key", nullable = false, length = 255) private String resourceKey;
	@Column(name = "source_material_key", length = 255) private String sourceMaterialKey;
	@Column(name = "source_user_id") private Long sourceUserId;
	@Column(name = "account_created_at") private Instant accountCreatedAt;
	@Column(name = "original_email_hash", length = 64) private String originalEmailHash;
	@Column(name = "requested_at", nullable = false) private Instant requestedAt;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DeletionStatus status;
	@Column(name = "retain_until") private Instant retainUntil;
	@Column(name = "policy_version", length = 100) private String policyVersion;
	@Column(nullable = false) private int attempts;
	@Column(nullable = false) private long generation;
	@Column(name = "lease_token", length = 36) private String leaseToken;
	@Column(name = "lease_until") private Instant leaseUntil;
	@Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
	@Column(name = "failure_code", length = 40) private String failureCode;
	@Column(name = "restore_epoch", length = 100) private String restoreEpoch;

	protected DeletionIntent() {}
	static DeletionIntent create(DeletionSnapshot snapshot) {
		snapshot.validate();
		DeletionIntent intent = new DeletionIntent();
		intent.kind = snapshot.kind(); intent.resourceKey = snapshot.resourceKey();
		intent.keyHash = DeletionJournal.hash(intent.kind.name() + "\n" + intent.resourceKey);
		intent.sourceMaterialKey = snapshot.sourceMaterialKey(); intent.sourceUserId = snapshot.sourceUserId();
		intent.accountCreatedAt = snapshot.accountCreatedAt(); intent.originalEmailHash = snapshot.originalEmailHash();
		intent.requestedAt = snapshot.requestedAt(); intent.nextAttemptAt = intent.requestedAt;
		intent.status = intent.kind == DeletionKind.ACCOUNT ? DeletionStatus.RECORDED : DeletionStatus.POLICY_PENDING;
		return intent;
	}
	void restore(Instant importedRequestedAt, String epoch) {
		if (importedRequestedAt.isBefore(requestedAt)) { requestedAt = importedRequestedAt; }
		if (epoch.equals(restoreEpoch)) { return; }
		restoreEpoch = epoch;
		if (kind == DeletionKind.ACCOUNT) { return; }
		generation++; attempts = 0; leaseToken = null; leaseUntil = null;
		failureCode = null; nextAttemptAt = requestedAt; status = DeletionStatus.POLICY_PENDING;
		// Keep the previous retention deadline: importing/reconfiguring must never shorten it.
	}
	void activate(DeletionProperties policy) {
		Instant proposed = requestedAt.plusSeconds(Math.multiplyExact(policy.days(kind).longValue(), 86400L));
		if (retainUntil == null || proposed.isAfter(retainUntil)) { retainUntil = proposed; }
		policyVersion = policy.policyVersion(); status = DeletionStatus.READY; failureCode = null;
	}
	void waitForPolicy() { status = DeletionStatus.POLICY_PENDING; leaseToken = null; leaseUntil = null; }
	void referenceInUse(Instant retryAt) {
		status = DeletionStatus.REFERENCE_PENDING; nextAttemptAt = retryAt; failureCode = "ACTIVE_REFERENCE";
		leaseToken = null; leaseUntil = null;
	}
	void recover(Instant now) {
		status = DeletionStatus.RETRY;
		leaseToken = null; leaseUntil = null; nextAttemptAt = now; failureCode = "LEASE_EXPIRED";
	}
	void claim(Instant now, java.time.Duration duration) {
		status = DeletionStatus.LEASED; attempts++; leaseToken = UUID.randomUUID().toString(); leaseUntil = now.plus(duration);
	}
	boolean owns(DeletionClaim claim, Instant now) {
		return status == DeletionStatus.LEASED && generation == claim.generation()
			&& kind == claim.kind() && resourceKey.equals(claim.resourceKey())
			&& leaseToken != null && leaseToken.equals(claim.token()) && leaseUntil.isAfter(now);
	}
	void done() { status = DeletionStatus.DONE; leaseToken = null; leaseUntil = null; failureCode = null; }
	void failed(Instant now, DeletionProperties policy, String code) {
		status = attempts >= policy.maxAttempts() ? DeletionStatus.FAILED : DeletionStatus.RETRY;
		nextAttemptAt = now.plus(policy.retryDelay()); leaseToken = null; leaseUntil = null; failureCode = code;
	}
	void exhaust() { status = DeletionStatus.FAILED; failureCode = "RETRY_EXHAUSTED"; }
	void waitUntilRetention() { nextAttemptAt = retainUntil; }
	public Long getId() { return id; }
	public DeletionStatus getStatus() { return status; }
	public DeletionKind getKind() { return kind; }
	public int getAttempts() { return attempts; }
	public Instant getRetainUntil() { return retainUntil; }
	public String getFailureCode() { return failureCode; }
	public long getGeneration() { return generation; }
	String keyHash() { return keyHash; }
	String resourceKey() { return resourceKey; }
	String sourceMaterialKey() { return sourceMaterialKey; }
	Long sourceUserId() { return sourceUserId; }
	Instant accountCreatedAt() { return accountCreatedAt; }
	String originalEmailHash() { return originalEmailHash; }
	Instant requestedAt() { return requestedAt; }
	Instant leaseUntil() { return leaseUntil; }
	Instant nextAttemptAt() { return nextAttemptAt; }
	String policyVersion() { return policyVersion; }
	DeletionSnapshot snapshot() { return new DeletionSnapshot(kind, resourceKey, sourceMaterialKey, sourceUserId,
		accountCreatedAt, originalEmailHash, requestedAt); }
	DeletionClaim claimSnapshot() { return new DeletionClaim(id, kind, resourceKey, leaseToken, generation); }
}
