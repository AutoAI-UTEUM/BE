package io.edupilot.guardian;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.global.error.*;
import io.edupilot.user.*;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class GuardianWebPersistence {
	private final UserRepository users;
	private final GuardianWebRequestRepository requests;
	private final GuardianWebProperties policy;
	private final GuardianWebSecrets secrets;
	private final Clock clock;
	public GuardianWebPersistence(UserRepository users, GuardianWebRequestRepository requests,
		GuardianWebProperties policy, GuardianWebSecrets secrets, Clock clock) {
		this.users = users; this.requests = requests; this.policy = policy; this.secrets = secrets; this.clock = clock;
	}
	public GuardianWebDtos.Link issue(Long userId) {
		User user = users.findByIdForUpdate(userId).orElseThrow(this::invalid);
		assertActive(user);
		if (user.isLegacyAccessExempt()) { throw conflict(); }
		Instant now = now();
		if (requests.countByUser_IdAndIssuedAtAfter(userId, now.minusSeconds(3600)) >= 3) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		// REQUIRES_NEW locks User before its first consistent read (the budget count above).
		// Every request writer locks the same User, so this snapshot includes prior account work.
		// A range FOR UPDATE here gap-locks an empty journal and deadlocks first issues across users.
		// Owner-first lock/expire/completion paths still require their current locking reads.
		requests.findByUser_IdOrderByIssuedAtDescIdDesc(userId).forEach(GuardianWebRequest::cancel);
		String raw = secrets.token();
		var row = GuardianWebRequest.issue(user, GuardianWebSecrets.hash(raw), policy, now);
		requests.saveAndFlush(row);
		return new GuardianWebDtos.Link(policy.portalBaseUrl().replaceAll("/+$", "") + "/guardian-consent#token=" + raw, row.expiresAt());
	}
	public GuardianWebDtos.Status view(String tokenHash) {
		GuardianWebRequest row = lock(tokenHash);
		return status(row);
	}
	public Attempt consent(String tokenHash, String noticeVersion, String phoneFingerprint) {
		GuardianWebRequest row = lock(tokenHash);
		if (!row.noticeVersion().equals(policy.noticeVersion()) || !row.noticeDigest().equals(policy.noticeDigest())
			|| !row.noticeVersion().equals(noticeVersion)) { throw new BusinessException(ErrorCode.GUARDIAN_NOTICE_CHANGED); }
		if (row.state() != GuardianWebRequest.State.AWAITING_CONSENT) { throw conflict(); }
		Instant now = now();
		Instant deadline = now.plus(policy.phoneTtl()).isBefore(row.expiresAt()) ? now.plus(policy.phoneTtl()) : row.expiresAt();
		String nonce = row.consent(phoneFingerprint, now, deadline);
		requests.flush();
		return new Attempt(row.id(), row.userId(), nonce, null, deadline);
	}
	public boolean sent(Attempt attempt, String reference) {
		var row = forCompletion(attempt, GuardianWebRequest.State.SENDING);
		if (row == null) { return false; }
		row.sent(reference); return true;
	}
	public Attempt verify(String tokenHash) {
		var row = lock(tokenHash);
		if (row.state() != GuardianWebRequest.State.PHONE_PENDING || !row.phoneExpiresAt().isAfter(now())) { throw conflict(); }
		if (row.codeAttempts() >= policy.maxCodeAttempts()) { throw conflict(); }
		String nonce = row.verifying(); requests.flush();
		return new Attempt(row.id(), row.userId(), nonce, row.providerReference(), row.phoneExpiresAt());
	}
	public boolean verified(Attempt attempt, GuardianPhoneProvider.Result result) {
		var row = forCompletion(attempt, GuardianWebRequest.State.VERIFYING);
		if (row == null) { return false; }
		if (result == GuardianPhoneProvider.Result.PHONE_CONFIRMED) { row.phoneConfirmed(now()); }
		else { row.mismatch(policy.maxCodeAttempts()); }
		// Neither phone control nor the self-declaration changes User age/guardian eligibility.
		return true;
	}
	public void providerFailure(Attempt attempt, GuardianWebRequest.State expected, GuardianPhoneProvider.Category failure) {
		var row = forCompletion(attempt, expected);
		if (row != null) { row.review(switch (failure) {
			case UNAVAILABLE -> GuardianWebRequest.Reason.PROVIDER_UNAVAILABLE;
			case REJECTED -> GuardianWebRequest.Reason.PROVIDER_REJECTED;
			case RESULT_UNKNOWN -> GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN;
		}); }
	}
	public GuardianWebDtos.Status dispute(String tokenHash) {
		var row = lock(tokenHash);
		row.review(GuardianWebRequest.Reason.DISPUTED); return status(row);
	}
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelUser(Long userId) {
		// Withdrawal already holds the User row. This method is called by its MANDATORY hook.
		requests.findByUserIdForUpdate(userId).forEach(GuardianWebRequest::cancel);
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public List<String> expiredIds() { return requests.expiredIds(now(), PageRequest.of(0, 100)); }
	public void expire(String id) {
		Long owner = requests.ownerOfId(id).orElse(null);
		if (owner == null) { return; }
		users.findByIdForUpdate(owner).orElseThrow(this::invalid);
		var row = requests.findByIdForUpdate(id).orElseThrow();
		if (row.state() == GuardianWebRequest.State.AWAITING_CONSENT && !row.expiresAt().isAfter(now())) { row.cancel(); }
		else if (row.phoneExpiresAt() != null && !row.phoneExpiresAt().isAfter(now())) {
			if (row.state() == GuardianWebRequest.State.SENDING || row.state() == GuardianWebRequest.State.VERIFYING) {
				row.review(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
			} else if (row.state() == GuardianWebRequest.State.PHONE_PENDING) { row.review(GuardianWebRequest.Reason.EXPIRED); }
		}
	}
	private GuardianWebRequest lock(String hash) {
		Long userId = requests.ownerOfToken(hash).orElseThrow(this::invalid);
		User user = users.findByIdForUpdate(userId).orElseThrow(this::invalid);
		if (!user.isActive() || user.isLegacyAccessExempt()) { throw invalid(); }
		var row = requests.findByTokenHashForUpdate(hash).orElseThrow(this::invalid);
		if (!row.userId().equals(userId)) { throw invalid(); }
		if (!row.usable(now())) { throw invalid(); }
		return row;
	}
	private GuardianWebRequest forCompletion(Attempt attempt, GuardianWebRequest.State expected) {
		User user = users.findByIdForUpdate(attempt.userId()).orElse(null);
		if (user == null || !user.isActive() || user.isLegacyAccessExempt()) { return null; }
		var row = requests.findByIdForUpdate(attempt.id()).orElse(null);
		if (row == null || !row.userId().equals(attempt.userId()) || !row.matchesAttempt(attempt.nonce(), expected) || !row.usable(now())
			|| !row.phoneExpiresAt().isAfter(now())) { return null; }
		return row;
	}
	private GuardianWebDtos.Status status(GuardianWebRequest row) {
		boolean currentNotice = row.noticeVersion().equals(policy.noticeVersion()) && row.noticeDigest().equals(policy.noticeDigest());
		return new GuardianWebDtos.Status(row.state(), row.noticeVersion(), row.noticeDigest(), currentNotice ? policy.noticeUrl() : null, row.expiresAt(),
			row.consentedAt() != null, row.state() == GuardianWebRequest.State.PHONE_CONFIRMED && row.phoneConfirmedAt() != null,
			false, row.reason());
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	private void assertActive(User user) {
		if (user.getStatus() == UserStatus.SUSPENDED) { throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED); }
		if (!user.isActive()) { throw new BusinessException(ErrorCode.USER_INACTIVE); }
	}
	private BusinessException invalid() { return new BusinessException(ErrorCode.GUARDIAN_LINK_INVALID); }
	private BusinessException conflict() { return new BusinessException(ErrorCode.GUARDIAN_STATE_CONFLICT); }
	public record Attempt(String id, Long userId, String nonce, String reference, Instant expiresAt) {
		@Override public String toString() { return "GuardianAttempt[REDACTED]"; }
	}
}
