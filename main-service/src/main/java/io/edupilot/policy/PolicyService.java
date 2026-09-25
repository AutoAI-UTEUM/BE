package io.edupilot.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.policy.dto.AgreedPolicyConsent;
import io.edupilot.policy.dto.PendingPolicyConsent;
import io.edupilot.policy.dto.PendingPolicyVersion;
import io.edupilot.policy.dto.PolicyConsentChoice;
import io.edupilot.policy.dto.PolicyConsentStats;
import io.edupilot.policy.dto.PolicyDocumentResponse;
import io.edupilot.policy.dto.PolicyDocumentSummary;
import io.edupilot.policy.dto.PublishPolicyRequest;
import io.edupilot.policy.dto.UserPolicyConsentsResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

@Service
public class PolicyService {
	private final PolicyDocumentRepository documents;
	private final PolicyConsentRepository consents;
	private final UserRepository users;
	private final Clock clock;

	public PolicyService(
		PolicyDocumentRepository documents, PolicyConsentRepository consents,
		UserRepository users, Clock clock
	) {
		this.documents = documents;
		this.consents = consents;
		this.users = users;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PolicyDocumentSummary> current() {
		return currentDocuments(clock.instant()).stream()
			.map(PolicyDocumentSummary::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public PolicyDocumentResponse detail(PolicyType type, String version) {
		return documents.findByTypeAndVersion(type, version)
			.map(PolicyDocumentResponse::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.POLICY_NOT_FOUND));
	}

	@Transactional(readOnly = true)
	public UserPolicyConsentsResponse userConsents(Long userId) {
		List<PolicyConsent> history = consents.findByUser_IdOrderByAgreedAtDescIdDesc(userId);
		List<PendingPolicyConsent> pending = currentDocuments(clock.instant()).stream()
			.filter(document -> history.stream().noneMatch(consent ->
				consent.getPolicyType() == document.getType()
					&& consent.getPolicyVersion().equals(document.getVersion())))
			.map(PendingPolicyConsent::from)
			.toList();
		return new UserPolicyConsentsResponse(
			pending, history.stream().map(AgreedPolicyConsent::from).toList()
		);
	}

	@Transactional(readOnly = true)
	public List<PendingPolicyVersion> pendingForLogin(Long userId) {
		return currentDocuments(clock.instant()).stream()
			.filter(document -> !consents.existsByUser_IdAndPolicyTypeAndPolicyVersion(
				userId, document.getType(), document.getVersion()))
			.map(document -> new PendingPolicyVersion(document.getType(), document.getVersion()))
			.toList();
	}

	@Transactional
	public UserPolicyConsentsResponse agree(
		Long userId, List<PolicyConsentChoice> choices, String ip, String userAgent
	) {
		if (choices == null || choices.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		User user = users.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		Instant now = clock.instant();
		Map<PolicyType, PolicyDocument> current = currentByType(now);
		for (PolicyConsentChoice choice : choices) {
			if (choice == null || choice.type() == null || choice.version() == null) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			PolicyDocument document = current.get(choice.type());
			if (document == null || !document.getVersion().equals(choice.version())) {
				throw new BusinessException(ErrorCode.POLICY_VERSION_MISMATCH);
			}
		}
		for (PolicyConsentChoice choice : choices) {
			if (!consents.existsByUser_IdAndPolicyTypeAndPolicyVersion(
				userId, choice.type(), choice.version())) {
				consents.saveAndFlush(PolicyConsent.create(
					user, choice.type(), choice.version(), now, ip, normalizeUserAgent(userAgent)
				));
			}
		}
		return userConsents(userId);
	}

	public SignupSelection validateSignup(List<PolicyConsentChoice> choices) {
		if (choices == null || choices.size() != PolicyType.values().length) {
			throw new BusinessException(ErrorCode.POLICY_CONSENT_REQUIRED);
		}
		Instant now = clock.instant();
		Map<PolicyType, PolicyDocument> current = currentByType(now);
		if (current.size() != PolicyType.values().length) {
			throw new BusinessException(ErrorCode.POLICY_CONSENT_REQUIRED);
		}
		Map<PolicyType, String> selected = new EnumMap<>(PolicyType.class);
		for (PolicyConsentChoice choice : choices) {
			if (choice == null || choice.type() == null || choice.version() == null
				|| selected.putIfAbsent(choice.type(), choice.version()) != null
				|| !current.get(choice.type()).getVersion().equals(choice.version())) {
				throw new BusinessException(ErrorCode.POLICY_CONSENT_REQUIRED);
			}
		}
		return new SignupSelection(
			selected.get(PolicyType.TERMS), selected.get(PolicyType.PRIVACY), now
		);
	}

	public void recordSignup(User user, SignupSelection selection, String ip, String userAgent) {
		for (PolicyType type : PolicyType.values()) {
			String version = type == PolicyType.TERMS
				? selection.termsVersion() : selection.privacyVersion();
			consents.save(PolicyConsent.create(
				user, type, version, selection.agreedAt(), ip, normalizeUserAgent(userAgent)
			));
		}
	}

	/** Immutable policy versions: corrections are published as a new version. */
	@Transactional
	public PolicyDocumentResponse publish(Long actorUserId, PublishPolicyRequest request) {
		Instant now = clock.instant();
		if (!request.effectiveAt().isAfter(now)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		String version = request.version().trim();
		if (documents.existsByTypeAndVersion(request.type(), version)) {
			throw new BusinessException(ErrorCode.POLICY_VERSION_EXISTS);
		}
		try {
			return PolicyDocumentResponse.from(documents.saveAndFlush(PolicyDocument.create(
				request.type(), version, request.title().trim(),
				request.content(), request.summary(), request.effectiveAt(), actorUserId, now
			)));
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.POLICY_VERSION_EXISTS);
		}
	}

	@Transactional(readOnly = true)
	public List<PolicyDocumentSummary> all(PolicyType type) {
		List<PolicyDocument> found = type == null
			? documents.findAllByOrderByEffectiveAtDescIdDesc()
			: documents.findByTypeOrderByEffectiveAtDescIdDesc(type);
		return found.stream().map(PolicyDocumentSummary::from).toList();
	}

	@Transactional(readOnly = true)
	public List<PolicyConsentStats> stats() {
		long activeUsers = users.countByStatus(UserStatus.ACTIVE);
		List<PolicyConsentStats> result = new ArrayList<>();
		for (PolicyDocument current : currentDocuments(clock.instant())) {
			long agreed = consents.countByPolicyTypeAndPolicyVersionAndUser_Status(
				current.getType(), current.getVersion(), UserStatus.ACTIVE
			);
			BigDecimal rate = activeUsers == 0 ? BigDecimal.ZERO
				: BigDecimal.valueOf(agreed).multiply(BigDecimal.valueOf(100))
					.divide(BigDecimal.valueOf(activeUsers), 2, RoundingMode.HALF_UP);
			result.add(new PolicyConsentStats(
				current.getType(), current.getVersion(), activeUsers, agreed, rate
			));
		}
		return result;
	}

	private List<PolicyDocument> currentDocuments(Instant now) {
		List<PolicyDocument> result = new ArrayList<>();
		for (PolicyType type : PolicyType.values()) {
			documents.findFirstByTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
				type, now
			).ifPresent(result::add);
		}
		return result;
	}

	private Map<PolicyType, PolicyDocument> currentByType(Instant now) {
		Map<PolicyType, PolicyDocument> result = new EnumMap<>(PolicyType.class);
		for (PolicyDocument document : currentDocuments(now)) {
			result.put(document.getType(), document);
		}
		return result;
	}

	private String normalizeUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return null;
		}
		return userAgent.substring(0, Math.min(userAgent.length(), 255));
	}

	public record SignupSelection(
		String termsVersion, String privacyVersion, Instant agreedAt
	) {
	}
}
