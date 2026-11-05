package io.edupilot.composer;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.AiUsage;
import io.edupilot.aiusage.*;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
@ConditionalOnProperty(name = "edupilot.composer.enabled", havingValue = "true")
public class ComposerAdmissionService {
	private final UserRepository users;
	private final AiUsageLogRepository usage;
	private final AiQuotaService quota;
	private final ComposerProperties policy;
	private final Clock clock;

	public ComposerAdmissionService(UserRepository users, AiUsageLogRepository usage,
		AiQuotaService quota, ComposerProperties policy, Clock clock) {
		this.users = users;
		this.usage = usage;
		this.quota = quota;
		this.policy = policy;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean reserve(Long userId, UserRole role, String requestId) {
		if (!policy.ready()) return false;
		// Shared DB row serializes admissions across instances, released before HTTP.
		users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
		quota.checkQuota(userId, role); // Includes verified email/age/AI consent; admins still have prediction limits.
		if (usage.existsByRequestId(requestId)) return false;
		Instant now = clock.instant();
		Instant day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
			.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		Instant minute = now.minusSeconds(60);
		Instant lease = now.minus(policy.timeout()).minusSeconds(1);
		Instant since = day.isBefore(minute) ? day : minute;
		if (lease.isBefore(since)) since = lease;
		long daily = 0, recent = 0, concurrent = 0;
		BigInteger cost = BigInteger.ZERO;
		for (var row : usage.findComposerUsage(userId, since)) {
			if (!row.getCreatedAt().isBefore(minute)) recent++;
			// Conservative fixed lease also counts quickly completed calls; crashes/cancels
			// cannot refund a slot or erase unknown provider charges.
			if (!row.getCreatedAt().isBefore(lease)) concurrent++;
			if (!row.getCreatedAt().isBefore(day)) {
				daily++;
				Long actual = row.getCostUsdTicks();
				cost = cost.add(BigInteger.valueOf(actual == null || actual < 0
					? policy.reservationUsdTicks() : actual));
			}
		}
		if (daily >= policy.dailyCalls() || recent >= policy.callsPerMinute()
			|| concurrent >= policy.concurrentPerUser()
			|| cost.add(BigInteger.valueOf(policy.reservationUsdTicks()))
				.compareTo(BigInteger.valueOf(policy.dailyBudgetUsdTicks())) > 0) return false;
		usage.saveAndFlush(AiUsageLog.create(userId, AiFeature.COMPOSER_PREDICTION, null, false, requestId));
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(Long userId, String requestId, AiUsage reported, boolean success) {
		users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
		var row = usage.findByUserIdAndFeatureAndRequestId(userId, AiFeature.COMPOSER_PREDICTION, requestId)
			.orElseThrow(() -> new IllegalStateException("Missing composer reservation"));
		row.finishComposer(safeUsage(reported), success);
		usage.flush();
	}

	static AiUsage safeUsage(AiUsage usage) {
		if (usage == null) return null;
		return new AiUsage(usage.model() != null && usage.model().length() <= 50 ? usage.model() : null,
			nonNegative(usage.inputTokens()), nonNegative(usage.outputTokens()),
			nonNegative(usage.reasoningTokens()), nonNegative(usage.costUsdTicks()));
	}
	private static Long nonNegative(Long value) { return value == null || value < 0 ? null : value; }
}
