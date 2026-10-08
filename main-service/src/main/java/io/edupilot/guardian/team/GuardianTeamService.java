package io.edupilot.guardian.team;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import io.edupilot.auth.PasswordResetRateLimiter;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.BirthdatePolicy;
import io.edupilot.guardian.team.forms.GuardianTeamForms;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

/** All case writers lock the account before the case; reviewer authority is checked in the same transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class GuardianTeamService {
	private static final int MAX_ORDINARY_EVENTS_PER_GENERATION = 98;
	private static final int MAX_TERMINAL_EVENTS_PER_GENERATION = 101;
	private final GuardianTeamProperties policy;
	private final UserRepository users;
	private final GuardianTeamRequestRepository requests;
	private final GuardianTeamEventRepository events;
	private final GuardianTeamOperationRepository operations;
	private final GuardianTeamSecrets secrets;
	private final GuardianTeamMailCleanup mailCleanup;
	private final Clock clock;
	private final PasswordResetRateLimiter publicLimits = new PasswordResetRateLimiter();

	public GuardianTeamService(GuardianTeamProperties policy, UserRepository users, GuardianTeamRequestRepository requests,
		GuardianTeamEventRepository events, GuardianTeamOperationRepository operations, GuardianTeamSecrets secrets,
		GuardianTeamMailCleanup mailCleanup, Clock clock) {
		this.policy = policy; this.users = users; this.requests = requests; this.events = events;
		this.operations = operations; this.secrets = secrets; this.mailCleanup = mailCleanup; this.clock = clock;
	}

	public GuardianTeamDtos.View intake(Long userId, GuardianTeamDtos.Intake body) {
		requireReady(); requireKey(body.idempotencyKey());
		String name = name(body.guardianName()), contact = contact(body.guardianContact());
		boolean childProvided = Boolean.TRUE.equals(body.guardianContactProvidedByChild());
		if ((name != null || contact != null) && !childProvided) { throw validation(); }
		User user = users.findByIdForUpdate(userId).orElseThrow(this::notFound); requireEligible(user);
		GuardianTeamRequest row = requests.findByUserForUpdate(userId).orElse(null);
		String key = "INTAKE:" + body.idempotencyKey();
		String digest = digest("INTAKE", name, contact, childProvided);
		if (row != null && replay(row, key, digest)) { return viewOf(row); }
		if (row != null) {
			expireDue(row);
			if (row.pending() || row.state() == GuardianTeamRequest.State.APPROVED) { throw conflict(); }
			if (events.countByRequestIdAndEventTypeAndRecordedAtAfter(row.id(), "REQUESTED", now().minusSeconds(3600)) >= 3) {
				throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
			}
			eraseLocalCopies(row); events.eraseDue(row.id(), now()); operations.eraseDue(row.id(), now());
			row.start(policy, configurationDigest(), now(), name, contact);
		} else {
			row = GuardianTeamRequest.create(user, policy, configurationDigest(), now(), name, contact);
			requests.saveAndFlush(row);
		}
		user.clearGuardianTeamApproval(true);
		record(row, key, digest, "REQUESTED", userId);
		return viewOf(row);
	}

	public GuardianTeamDtos.View self(Long userId) {
		requireReady(); User user = users.findByIdForUpdate(userId).orElseThrow(this::notFound); requireActive(user);
		var row = requests.findByUserForUpdate(userId).orElseThrow(this::notFound);
		expireDue(row); return viewOf(row);
	}

	/** Classifies the current account without creating a case or collecting guardian information. */
	public GuardianTeamDtos.Entry entry(Long userId) {
		User user = users.findByIdForUpdate(userId).orElseThrow(this::notFound); requireActive(user);
		var requirement = requirement(user);
		boolean available = policy.ready();
		if (!available || requirement != GuardianTeamDtos.Requirement.REQUIRED) {
			return new GuardianTeamDtos.Entry(requirement, available, false, available ? policy.replyChannel() : null, null);
		}
		var row = requests.findByUserForUpdate(userId).orElse(null);
		if (row != null) { expireDue(row); }
		boolean canStart = row == null || !row.pending() && row.state() != GuardianTeamRequest.State.APPROVED;
		return new GuardianTeamDtos.Entry(requirement, true, canStart, policy.replyChannel(), row == null ? null : viewOf(row));
	}

	private GuardianTeamDtos.Requirement requirement(User user) {
		if (user.isLegacyAccessExempt() || BirthdatePolicy.guardianNotRequired(user.getDateOfBirth(), clock)) {
			return GuardianTeamDtos.Requirement.NOT_REQUIRED;
		}
		try { BirthdatePolicy.validate(user.getDateOfBirth(), clock); }
		catch (BusinessException invalidDate) { return GuardianTeamDtos.Requirement.BIRTHDATE_REQUIRED; }
		return GuardianTeamDtos.Requirement.REQUIRED;
	}

	public GuardianTeamDtos.Link issueLink(Long userId, String id, GuardianTeamDtos.Mutation body) {
		requireReady(); var row = lockSelf(userId, id);
		String key = "LINK:" + requireKey(body.idempotencyKey());
		String digest = digest("LINK", body.generation(), body.revision());
		if (replay(row, key, digest)) { return new GuardianTeamDtos.Link(null, row.tokenExpiresAt(), true, status(row)); }
		expect(row, body.generation(), body.revision()); requireCurrent(row); requirePending(row);
		String raw = secrets.token(); row.issueToken(GuardianTeamSecrets.hash(raw), now(), policy.linkTtl());
		record(row, key, digest, "LINK_ISSUED", userId);
		return new GuardianTeamDtos.Link(policy.portalBaseUrl().replaceAll("/+$", "") + "/guardian-consent#token=" + raw,
			row.tokenExpiresAt(), false, status(row));
	}

	public GuardianTeamDtos.View publicView(GuardianTeamDtos.Token body, String ip) {
		requireReady(); limit(ip); String hash = tokenHash(body.token()); var row = lockToken(hash);
		if (!row.tokenUsable(hash, now())) { throw invalidToken(); }
		requireCurrent(row); return viewOf(row);
	}

	public GuardianTeamDtos.Status consent(GuardianTeamDtos.Consent body, String ip) {
		requireReady(); limit(ip); String hash = tokenHash(body.token()); var row = lockToken(hash);
		if (row.tokenExpiresAt() == null || !row.tokenExpiresAt().isAfter(now())) { throw invalidToken(); }
		String key = "CONSENT:" + requireKey(body.idempotencyKey());
		String name = name(body.guardianName()), contact = contact(body.guardianContact());
		String scopes = scopes(body.scopes(), body.accepted());
		String digest = digest("CONSENT", hash, body.generation(), body.revision(), body.noticeVersion(), body.noticeDigest(),
			body.accepted(), body.declaresLegalGuardian(), body.relationship(), scopes, name, contact);
		if (replay(row, key, digest)) { return status(row); }
		if (!row.tokenUsable(hash, now())) { throw invalidToken(); }
		expect(row, body.generation(), body.revision()); requireCurrent(row); matchNotice(row, body.noticeVersion(), body.noticeDigest());
		if (!body.accepted()) {
			if (name != null || contact != null || (body.scopes() != null && !body.scopes().isEmpty())) { throw validation(); }
			row.end(GuardianTeamRequest.State.REJECTED, GuardianTeamRequest.Reason.CONSENT_DECLINED, now());
			row.user().clearGuardianTeamApproval(false); eraseLocalCopies(row);
			record(row, key, digest, "CONSENT_DECLINED", null); return status(row);
		}
		if (!body.declaresLegalGuardian() || body.relationship() == null) { throw validation(); }
		row.declare(name, contact, body.relationship(), scopes, now());
		record(row, key, digest, "WEB_DECLARED", null);
		// A click or declaration never changes User eligibility or serves as the explicit email response.
		return status(row);
	}

	public GuardianTeamDtos.ListResponse list(Long reviewerId, int page, int size) {
		requireReady(); requireReviewer(users.findByIdForUpdate(reviewerId).orElseThrow(this::denied));
		if (page < 0 || size < 1 || size > 100) { throw validation(); }
		var result = requests.listOrdered(PageRequest.of(page, size));
		return new GuardianTeamDtos.ListResponse(result.getContent().stream().map(this::status).toList(), page, size,
			result.getTotalElements(), result.getTotalPages());
	}

	public GuardianTeamDtos.Detail detail(Long reviewerId, String id) {
		requireReady(); var row = lockReviewer(reviewerId, id); expireDue(row);
		return new GuardianTeamDtos.Detail(status(row), row.userId(), row.guardianName(), row.guardianContact(), row.contactOrigin(),
			row.generationStartedAt(), scopeSet(row.declaredScopes()), row.configurationDigest().equals(configurationDigest()) ? policy.replyChannel() : null,
			row.relationship(), row.confirmationMethod(), row.evidenceReference(),
			events.findByRequestIdOrderByRecordedAtAscIdAsc(row.id(), PageRequest.of(0, 200)).stream()
				.map(e -> new GuardianTeamDtos.Event(e.generation(), e.revision(), e.eventType(), e.state(), e.actorId(), e.recordedAt())).toList(),
			forms(row));
	}

	public GuardianTeamDtos.Status confirm(Long reviewerId, String id, GuardianTeamDtos.Confirmation body) {
		requireReady(); var row = lockReviewer(reviewerId, id);
		String key = "CONFIRM:" + requireKey(body.idempotencyKey()); String scopes = scopes(body.scopes(), true);
		String digest = digest("CONFIRM", body.generation(), body.revision(), body.method(), body.evidenceReference(),
			body.responseReceivedAt(), body.noticeVersion(), body.noticeDigest(), body.relationship(), scopes,
			body.requestReferenceMatched(), body.responseExplicitlyConsents(), body.legalGuardianDeclarationConfirmed(),
			body.noticeAndScopesMatched(), body.confirmationMethodChecked());
		if (replay(row, key, digest)) { return status(row); }
		expect(row, body.generation(), body.revision()); requireCurrent(row); requirePending(row);
		matchNotice(row, body.noticeVersion(), body.noticeDigest());
		if (body.method() == null || body.relationship() == null || body.evidenceReference() == null
			|| !body.evidenceReference().matches("[A-Za-z0-9_.:-]{1,100}") || body.responseReceivedAt() == null
			|| body.responseReceivedAt().isAfter(now()) || body.responseReceivedAt().isBefore(row.generationStartedAt())
			|| !body.requestReferenceMatched() || !body.responseExplicitlyConsents() || !body.legalGuardianDeclarationConfirmed()
			|| !body.noticeAndScopesMatched() || !body.confirmationMethodChecked()) { throw validation(); }
		if (("EMAIL_REPLY".equals(policy.replyChannel()) && body.method() != GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY)
			|| ("PHONE_CALLBACK".equals(policy.replyChannel()) && body.method() != GuardianTeamRequest.ConfirmationMethod.PHONE)) { throw validation(); }
		if (row.webDeclaredAt() != null && (!Objects.equals(row.declaredScopes(), scopes) || row.relationship() != body.relationship())) {
			throw conflict();
		}
		row.confirm(body.method(), body.evidenceReference(), body.responseReceivedAt(), reviewerId, body.relationship(), scopes, now());
		record(row, key, digest, "EXPLICIT_RESPONSE_CONFIRMED", reviewerId); return status(row);
	}

	public GuardianTeamDtos.Status decide(Long reviewerId, String id, GuardianTeamDtos.Decision body) {
		requireReady(); var row = lockReviewer(reviewerId, id);
		String key = "DECIDE:" + requireKey(body.idempotencyKey());
		String digest = digest("DECIDE", body.generation(), body.revision(), body.decision(), body.reason(), body.relationshipChecked(),
			body.guardianContactChecked(), body.evidenceReferenceChecked(), body.noticeAndScopesChecked());
		if (replay(row, key, digest)) { return status(row); }
		expect(row, body.generation(), body.revision()); requireCurrent(row); requirePending(row);
		if (body.decision() == null) { throw validation(); }
		switch (body.decision()) {
			case APPROVE -> {
				if (row.state() != GuardianTeamRequest.State.REVIEW_PENDING || row.explicitResponseAt() == null
					|| !row.legalMethodChecked() || row.evidenceReference() == null || !body.relationshipChecked()
					|| !body.guardianContactChecked() || !body.evidenceReferenceChecked() || !body.noticeAndScopesChecked()
					|| !scopeSet(row.declaredScopes()).containsAll(policy.requiredScopes())) { throw conflict(); }
				requireEligible(row.user()); row.approve(reviewerId, now(), policy.approvalValidity(), policy.approvedEvidenceRetention());
				row.user().recordGuardianTeamApproval(row.approvedUntil(), scopeSet(row.declaredScopes()).contains("EXTERNAL_AI"));
				row.user().recordGuardianTeamPolicyDigest(policy.configurationDigest());
				events.setGenerationDeadline(row.id(), row.generation(), row.evidenceEraseDueAt());
				// Remove superseded intake/declaration input digests as well as plain contacts after review.
				operations.eraseForRequest(row.id());
				mailCleanup.purgeForRequest(row.id());
			}
			case REJECT -> {
				requireReason(body.reason(), Set.of(GuardianTeamRequest.Reason.RELATIONSHIP_UNCONFIRMED,
					GuardianTeamRequest.Reason.CONSENT_DECLINED, GuardianTeamRequest.Reason.INCOMPLETE_RESPONSE));
				row.end(GuardianTeamRequest.State.REJECTED, body.reason(), now()); row.user().clearGuardianTeamApproval(false);
				eraseLocalCopies(row);
			}
			case NEEDS_INFORMATION -> {
				requireReason(body.reason(), Set.of(GuardianTeamRequest.Reason.INCOMPLETE_RESPONSE,
					GuardianTeamRequest.Reason.RELATIONSHIP_UNCONFIRMED));
				row.needsInformation(body.reason()); row.user().clearGuardianTeamApproval(true); mailCleanup.purgeForRequest(row.id());
			}
		}
		record(row, key, digest, body.decision().name(), reviewerId); return status(row);
	}

	public GuardianTeamDtos.Status revoke(Long reviewerId, String id, GuardianTeamDtos.Revoke body) {
		requireReady(); var row = lockReviewer(reviewerId, id);
		String key = "REVOKE:" + requireKey(body.idempotencyKey());
		String digest = digest("REVOKE", body.generation(), body.revision(), body.reason());
		if (replay(row, key, digest)) { return status(row); }
		expect(row, body.generation(), body.revision());
		requireReason(body.reason(), Set.of(GuardianTeamRequest.Reason.OPERATOR_REVOKED, GuardianTeamRequest.Reason.CONSENT_DECLINED,
			GuardianTeamRequest.Reason.RELATIONSHIP_UNCONFIRMED));
		if (row.state() != GuardianTeamRequest.State.APPROVED && !row.pending()) { throw conflict(); }
		row.end(GuardianTeamRequest.State.REVOKED, body.reason(), now()); row.user().clearGuardianTeamApproval(false);
		eraseLocalCopies(row); record(row, key, digest, "REVOKED", reviewerId); return status(row);
	}

	public GuardianTeamDtos.Status withdrawRequest(Long userId, String id, GuardianTeamDtos.Mutation body) {
		requireReady(); var row = lockSelf(userId, id);
		String key = "WITHDRAW:" + requireKey(body.idempotencyKey());
		String digest = digest("WITHDRAW", body.generation(), body.revision());
		if (replay(row, key, digest)) { return status(row); }
		expect(row, body.generation(), body.revision());
		if (!row.pending() && row.state() != GuardianTeamRequest.State.APPROVED) { throw conflict(); }
		row.end(GuardianTeamRequest.State.REVOKED, GuardianTeamRequest.Reason.REQUESTER_WITHDREW, now());
		row.user().clearGuardianTeamApproval(false); eraseLocalCopies(row);
		record(row, key, digest, "SELF_WITHDRAWN", userId); return status(row);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelOnWithdrawal(Long userId) {
		var row = requests.findByUserForUpdate(userId).orElse(null);
		if (row == null) { return; }
		row.end(GuardianTeamRequest.State.WITHDRAWN, GuardianTeamRequest.Reason.ACCOUNT_WITHDRAWN, now());
		row.user().clearGuardianTeamApproval(false); eraseLocalCopies(row);
		events.setGenerationDeadline(row.id(), row.generation(), now()); events.eraseDue(row.id(), now());
		operations.eraseForRequest(row.id());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public List<String> recoveryIds() {
		var ids = new TreeSet<>(requests.dueIds(now(), PageRequest.of(0, 100)));
		ids.addAll(events.dueRequestIds(now(), PageRequest.of(0, 100)));
		return ids.stream().limit(100).toList();
	}

	public void recover(String id) {
		Long owner = requests.ownerOfId(id).orElse(null); if (owner == null) { return; }
		users.findByIdForUpdate(owner).orElseThrow(this::notFound);
		var row = requests.findByIdForUpdate(id).orElseThrow(this::notFound);
		expireDue(row); events.eraseDue(id, now()); operations.eraseDue(id, now());
	}

	private GuardianTeamRequest lockSelf(Long userId, String id) {
		User user = users.findByIdForUpdate(userId).orElseThrow(this::notFound); requireActive(user);
		var row = requests.findByIdForUpdate(id).orElseThrow(this::notFound);
		if (!row.userId().equals(userId)) { throw notFound(); }
		return row;
	}
	private GuardianTeamRequest lockToken(String hash) {
		Long owner = requests.ownerOfToken(hash).orElseThrow(GuardianTeamService::invalidToken);
		User user = users.findByIdForUpdate(owner).orElseThrow(GuardianTeamService::invalidToken);
		if (!user.isActive() || user.isLegacyAccessExempt()) { throw invalidToken(); }
		return requests.findByTokenForUpdate(hash).orElseThrow(GuardianTeamService::invalidToken);
	}
	private GuardianTeamRequest lockReviewer(Long reviewerId, String id) {
		Long owner = requests.ownerOfId(id).orElseThrow(this::notFound);
		List<Long> ids = new TreeSet<>(List.of(owner, reviewerId)).stream().toList();
		User reviewer = null, account = null;
		for (Long accountId : ids) {
			User locked = users.findByIdForUpdate(accountId).orElseThrow(this::denied);
			if (accountId.equals(reviewerId)) { reviewer = locked; }
			if (accountId.equals(owner)) { account = locked; }
		}
		requireReviewer(reviewer);
		if (owner.equals(reviewerId)) { throw denied(); }
		var row = requests.findByIdForUpdate(id).orElseThrow(this::notFound);
		if (!row.userId().equals(owner)) { throw conflict(); }
		if (account == null) { throw notFound(); }
		return row;
	}
	private void requireReviewer(User reviewer) {
		if (reviewer == null || reviewer.getRole() != UserRole.ADMIN || reviewer.getStatus() != UserStatus.ACTIVE
			|| !policy.reviewerIds().contains(reviewer.getId())) { throw denied(); }
	}
	private void requireEligible(User user) {
		requireActive(user);
		if (user.isLegacyAccessExempt() || user.getDateOfBirth() == null || BirthdatePolicy.guardianNotRequired(user.getDateOfBirth(), clock)) {
			throw conflict();
		}
		BirthdatePolicy.validate(user.getDateOfBirth(), clock);
	}
	private void requireActive(User user) { if (!user.isActive()) { throw new BusinessException(ErrorCode.USER_INACTIVE); } }
	private void requirePending(GuardianTeamRequest row) {
		if (!row.pending() || !row.requestExpiresAt().isAfter(now()) || row.unconfirmedExpired(now())) { throw conflict(); }
	}
	private void requireCurrent(GuardianTeamRequest row) {
		if (!row.configurationDigest().equals(configurationDigest())) { throw new BusinessException(ErrorCode.GUARDIAN_TEAM_CONFIGURATION_CHANGED); }
	}
	private void expect(GuardianTeamRequest row, long generation, long revision) {
		if (row.generation() != generation || row.revision() != revision) { throw conflict(); }
	}
	private void matchNotice(GuardianTeamRequest row, String version, String digest) {
		if (!row.noticeVersion().equals(version) || !row.noticeDigest().equals(digest)) {
			throw new BusinessException(ErrorCode.GUARDIAN_NOTICE_CHANGED);
		}
	}
	private void expireDue(GuardianTeamRequest row) {
		if (row.requestExpired(now()) || row.unconfirmedExpired(now()) || row.approvedExpired(now())) {
			row.end(GuardianTeamRequest.State.EXPIRED, GuardianTeamRequest.Reason.DEADLINE_EXPIRED, now());
			row.user().clearGuardianTeamApproval(false); eraseLocalCopies(row);
			events.setGenerationDeadline(row.id(), row.generation(), now()); events.eraseDue(row.id(), now());
		} else if (row.evidenceExpired(now())) {
			// Keep the finite approval facts until approval expiry, while erasing evidence according to its own period.
			row.eraseEvidence(now()); mailCleanup.purgeForRequest(row.id());
			events.eraseDue(row.id(), now()); operations.eraseDue(row.id(), now());
		}
	}
	private void eraseLocalCopies(GuardianTeamRequest row) {
		mailCleanup.purgeForRequest(row.id()); operations.eraseForRequest(row.id());
	}
	private boolean replay(GuardianTeamRequest row, String key, String digest) {
		var prior = operations.findByRequestIdAndOperationKey(row.id(), key).orElse(null);
		if (prior == null) { return false; }
		if (!prior.inputDigest().equals(digest)) { throw conflict(); }
		return true;
	}
	private void record(GuardianTeamRequest row, String key, String digest, String type, Long actor) {
		// Reserve a bounded terminal slot so an exhausted invitation budget cannot prevent revocation/erasure.
		boolean terminal = Set.of("REVOKED", "SELF_WITHDRAWN", "CONSENT_DECLINED", "REJECT").contains(type);
		int maximum = terminal ? MAX_TERMINAL_EVENTS_PER_GENERATION : MAX_ORDINARY_EVENTS_PER_GENERATION;
		if (events.countByRequestIdAndGeneration(row.id(), row.generation()) >= maximum) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		Instant deadline = row.evidenceEraseDueAt() == null ? row.requestExpiresAt() : row.evidenceEraseDueAt();
		operations.save(GuardianTeamOperation.record(row.id(), key, digest, now(), deadline));
		events.save(GuardianTeamEvent.record(row, type, actor, now(), deadline)); requests.flush();
	}
	private GuardianTeamDtos.Status status(GuardianTeamRequest row) {
		boolean approved = policy.ready() && row.configurationDigest().equals(configurationDigest())
			&& row.state() == GuardianTeamRequest.State.APPROVED && row.approvedUntil() != null && row.approvedUntil().isAfter(now())
			&& row.user().isActive() && row.user().getAgeVerificationState() == io.edupilot.guardian.AgeVerificationState.TEAM_APPROVED
			&& Objects.equals(row.approvedUntil(), row.user().getGuardianApprovedUntil())
			&& Objects.equals(configurationDigest(), row.user().getGuardianApprovalPolicyDigest());
		return new GuardianTeamDtos.Status(row.id(), row.generation(), row.revision(), row.state(), row.noticeVersion(), row.noticeDigest(),
			row.requestExpiresAt(), row.unconfirmedEraseDueAt(), row.webDeclaredAt(), row.explicitResponseAt(), row.approvedUntil(),
			row.configurationDigest().equals(configurationDigest()), approved, approved && row.user().isGuardianAiConsentAllowed(), row.reasonCode());
	}
	private GuardianTeamDtos.View viewOf(GuardianTeamRequest row) {
		boolean current = row.configurationDigest().equals(configurationDigest());
		return new GuardianTeamDtos.View(status(row), current ? policy.noticeUrl() : null, policy.requiredScopes(), policy.optionalAiScope(),
			current ? policy.replyChannel() : null,
			current ? applicableForms(row) : Map.of());
	}
	private Map<String, String> applicableForms(GuardianTeamRequest row) {
		Map<String, String> all = forms(row);
		List<String> keys = switch (row.state()) {
			case AWAITING_CONSENT -> List.of("receipt", "consent_email", "reply_form");
			case DECLARED, REVIEW_PENDING -> List.of("review_pending", "reply_form");
			case NEEDS_INFORMATION -> List.of("needs_information", "consent_email", "reply_form");
			case APPROVED -> List.of("approved");
			case REJECTED -> List.of("rejected");
			case REVOKED, WITHDRAWN -> List.of("revoked");
			case EXPIRED -> List.of("expired");
		};
		java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
		keys.forEach(key -> result.put(key, all.get(key)));
		return java.util.Collections.unmodifiableMap(result);
	}
	private Map<String, String> forms(GuardianTeamRequest row) {
		if (!row.configurationDigest().equals(configurationDigest())) { return Map.of(); }
		return GuardianTeamForms.renderAll(new GuardianTeamForms.FormContext(row.id(), row.generation(), row.noticeVersion(), row.noticeDigest(),
			policy.noticeUrl(), policy.collectionItemsText(), policy.purposesText(), policy.retentionText(), policy.refusalText(),
			policy.replyContact(), policy.replyChannel(), policy.requiredScopes(), policy.optionalAiScope(), policy.optionalConsentText(), row.requestExpiresAt()));
	}
	private String configurationDigest() {
		return policy.configurationDigest();
	}
	private String scopes(Set<String> values, boolean accepted) {
		if (values == null) { values = Set.of(); }
		if (values.stream().anyMatch(Objects::isNull)) { throw validation(); }
		Set<String> allowed = policy.optionalAiScope() == null || policy.optionalAiScope().isBlank()
			? Set.of("SERVICE") : Set.of("SERVICE", "EXTERNAL_AI");
		if (values.size() > 2 || !allowed.containsAll(values) || accepted && !values.containsAll(policy.requiredScopes())) { throw validation(); }
		return String.join(",", new TreeSet<>(values));
	}
	private static Set<String> scopeSet(String value) { return value == null || value.isBlank() ? Set.of() : Set.of(value.split(",")); }
	private static String name(String value) {
		if (value == null || value.isBlank()) { return null; }
		if (value.length() > 100 || value.chars().anyMatch(Character::isISOControl)) { throw validation(); }
		return value.strip();
	}
	private static String contact(String value) {
		if (value == null || value.isBlank()) { return null; }
		String clean = value.strip();
		if (clean.length() > 254 || !(clean.matches("[^\\s@<>]{1,64}@[^\\s@<>]{1,190}\\.[^\\s@<>]{2,63}") || clean.matches("\\+[1-9][0-9]{7,14}"))) {
			throw validation();
		}
		return clean;
	}
	private static String requireKey(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}")) { throw validation(); }
		return value;
	}
	private static String tokenHash(String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw invalidToken(); }
		return GuardianTeamSecrets.hash(token);
	}
	private static String digest(Object... values) {
		// Length-prefixing prevents ambiguous delimiters in otherwise bounded submitted data.
		StringBuilder canonical = new StringBuilder();
		for (Object value : values) { String text = Objects.toString(value, ""); canonical.append(text.length()).append(':').append(text); }
		return GuardianTeamSecrets.hash(canonical.toString());
	}
	private static void requireReason(GuardianTeamRequest.Reason reason, Set<GuardianTeamRequest.Reason> allowed) {
		if (reason == null || !allowed.contains(reason)) { throw validation(); }
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	private void limit(String ip) { if (!publicLimits.allowConfirm(ip)) { throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED); } }
	private void requireReady() { if (!policy.ready()) { throw new BusinessException(ErrorCode.GUARDIAN_TEAM_UNAVAILABLE); } }
	private static BusinessException invalidToken() { return new BusinessException(ErrorCode.GUARDIAN_LINK_INVALID); }
	private BusinessException notFound() { return new BusinessException(ErrorCode.GUARDIAN_TEAM_REQUEST_NOT_FOUND); }
	private BusinessException denied() { return new BusinessException(ErrorCode.ACCESS_DENIED); }
	private static BusinessException validation() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
	private static BusinessException conflict() { return new BusinessException(ErrorCode.GUARDIAN_STATE_CONFLICT); }
}
