package io.edupilot.guardian;

import java.net.URI;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.PasswordResetRateLimiter;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

/** Provider IO never holds a DB transaction. Its result is committed only under the original attempt fence. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class GuardianWebService {

	private final GuardianWebProperties policy;
	private final GuardianPhoneProvider provider;
	private final GuardianWebPersistence persistence;
	private final GuardianWebSecrets secrets;
	// Separate windows: guardian traffic must not consume password-reset quotas.
	private final PasswordResetRateLimiter limits = new PasswordResetRateLimiter();

	public GuardianWebService(GuardianWebProperties policy, GuardianPhoneProvider provider,
		GuardianWebPersistence persistence, GuardianWebSecrets secrets) {
		this.policy = policy;
		this.provider = provider;
		this.persistence = persistence;
		this.secrets = secrets;
	}

	public GuardianWebDtos.Link issue(Long userId, String ip) {
		requireProvider();
		if (!limits.allowRequest("guardian-user:" + userId, ip)) { throw limited(); }
		return persistence.issue(userId);
	}

	public GuardianWebDtos.Status view(GuardianWebDtos.Token body, String ip) {
		requireConfigured();
		limitPublic(ip);
		return persistence.view(tokenHash(body == null ? null : body.token()));
	}

	public GuardianWebDtos.Status consent(GuardianWebDtos.Consent body, String ip) {
		requireProvider();
		limitPublic(ip);
		if (body == null || !body.accepted() || !body.declaresLegalGuardian()
			|| body.phone() == null || !body.phone().matches("\\+[1-9][0-9]{7,14}")) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		String hash = tokenHash(body.token());
		var attempt = persistence.consent(hash, body.noticeVersion(), secrets.phoneFingerprint(body.phone()));
		GuardianPhoneProvider.Receipt receipt;
		try {
			receipt = provider.send(body.phone(), attempt.nonce(), attempt.expiresAt());
		} catch (RuntimeException failure) {
			persistence.providerFailure(attempt, GuardianWebRequest.State.SENDING, category(failure));
			return persistence.view(hash);
		}
		if (receipt == null || receipt.reference() == null || !receipt.reference().matches("[A-Za-z0-9_.:-]{1,100}")) {
			persistence.providerFailure(attempt, GuardianWebRequest.State.SENDING, GuardianPhoneProvider.Category.RESULT_UNKNOWN);
		} else if (!persistence.sent(attempt, receipt.reference())) {
			throw invalid();
		}
		return persistence.view(hash);
	}

	public GuardianWebDtos.Status verify(GuardianWebDtos.Code body, String ip) {
		requireProvider();
		limitPublic(ip);
		if (body == null || body.code() == null || !body.code().matches("[0-9]{4,8}")) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		String hash = tokenHash(body.token());
		var attempt = persistence.verify(hash);
		GuardianPhoneProvider.Result result;
		try {
			result = provider.verify(attempt.reference(), body.code(), attempt.nonce(), attempt.expiresAt());
		} catch (RuntimeException failure) {
			persistence.providerFailure(attempt, GuardianWebRequest.State.VERIFYING, category(failure));
			return persistence.view(hash);
		}
		if (result == null) {
			persistence.providerFailure(attempt, GuardianWebRequest.State.VERIFYING, GuardianPhoneProvider.Category.RESULT_UNKNOWN);
		} else if (!persistence.verified(attempt, result)) {
			throw invalid();
		}
		return persistence.view(hash);
	}

	public GuardianWebDtos.Status dispute(GuardianWebDtos.Token body, String ip) {
		requireConfigured();
		limitPublic(ip);
		return persistence.dispute(tokenHash(body == null ? null : body.token()));
	}

	private void requireProvider() {
		requireConfigured();
		boolean connected;
		try { connected = provider.connected(); }
		catch (RuntimeException unavailable) { connected = false; }
		if (!connected) { throw unavailable(); }
	}

	private void requireConfigured() {
		if (!policy.enabled() || !positiveBounded(policy.linkTtl(), Duration.ofHours(24))
			|| !positiveBounded(policy.phoneTtl(), policy.linkTtl()) || policy.maxCodeAttempts() < 1
			|| policy.maxCodeAttempts() > 10 || !httpsUrl(policy.portalBaseUrl()) || !httpsUrl(policy.noticeUrl())
			|| policy.noticeVersion() == null || !policy.noticeVersion().matches("[A-Za-z0-9_.-]{1,100}")
			|| policy.noticeDigest() == null || !policy.noticeDigest().matches("[0-9a-f]{64}")) {
			throw unavailable();
		}
	}

	private static boolean positiveBounded(Duration value, Duration maximum) {
		return value != null && maximum != null && !value.isNegative() && !value.isZero() && value.compareTo(maximum) <= 0;
	}

	private static boolean httpsUrl(String raw) {
		if (raw == null || raw.length() > 2000) { return false; }
		try {
			URI uri = URI.create(raw);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
				&& uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
		} catch (IllegalArgumentException invalid) { return false; }
	}

	private static String tokenHash(String raw) {
		if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) { throw invalid(); }
		return GuardianWebSecrets.hash(raw);
	}

	private void limitPublic(String ip) { if (!limits.allowConfirm(ip)) { throw limited(); } }
	private static GuardianPhoneProvider.Category category(RuntimeException failure) {
		return failure instanceof GuardianPhoneProvider.Failure typed && typed.category() != null
			? typed.category() : GuardianPhoneProvider.Category.RESULT_UNKNOWN;
	}
	private static BusinessException unavailable() { return new BusinessException(ErrorCode.GUARDIAN_VERIFICATION_UNAVAILABLE); }
	private static BusinessException invalid() { return new BusinessException(ErrorCode.GUARDIAN_LINK_INVALID); }
	private static BusinessException limited() { return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED); }
}
