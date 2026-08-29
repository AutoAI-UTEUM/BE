package io.edupilot.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.dto.AdminAiUsageDailyResponse;
import io.edupilot.admin.dto.AdminAiUsageFeatureResponse;
import io.edupilot.admin.dto.AdminAiUsageSummaryResponse;
import io.edupilot.admin.dto.AdminAiUsageUserListResponse;
import io.edupilot.admin.dto.AdminAiUsageUserResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserStatus;

@Service
public class AdminAiUsageService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int MAX_RANGE_DAYS = 92;
	private static final int DEFAULT_USER_LIMIT = 20;
	private static final int MAX_USER_LIMIT = 100;

	private final AiUsageLogRepository usageLogRepository;
	private final Clock clock;

	public AdminAiUsageService(
		AiUsageLogRepository usageLogRepository,
		Clock clock
	) {
		this.usageLogRepository = usageLogRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminAiUsageSummaryResponse summary(LocalDate from, LocalDate to) {
		DateRange range = dateRange(from, to);
		var daily = usageLogRepository.aggregateDaily(
			range.fromUtc(),
			range.toExclusiveUtc()
		).stream().map(row -> new AdminAiUsageDailyResponse(
			row.getUsageDate(),
			row.getCallCount(),
			row.getSuccessCount(),
			row.getFailCount(),
			row.getInputTokens(),
			row.getOutputTokens(),
			row.getReasoningTokens()
		)).toList();
		var features = usageLogRepository.aggregateByFeature(
			range.fromUtc(),
			range.toExclusiveUtc()
		).stream().map(row -> new AdminAiUsageFeatureResponse(
			AiFeature.valueOf(row.getFeature()),
			row.getCallCount(),
			row.getInputTokens(),
			row.getOutputTokens(),
			row.getReasoningTokens()
		)).toList();
		return new AdminAiUsageSummaryResponse(daily, features);
	}

	@Transactional(readOnly = true)
	public AdminAiUsageUserListResponse users(
		LocalDate from,
		LocalDate to,
		Integer limit
	) {
		int effectiveLimit = limit == null ? DEFAULT_USER_LIMIT : limit;
		if (effectiveLimit < 1 || effectiveLimit > MAX_USER_LIMIT) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		DateRange range = dateRange(from, to);
		var users = usageLogRepository.aggregateByUser(
			range.fromUtc(),
			range.toExclusiveUtc(),
			effectiveLimit
		).stream().map(row -> new AdminAiUsageUserResponse(
			row.getUserId(),
			row.getEmail(),
			row.getName(),
			UserStatus.valueOf(row.getStatus()),
			row.getCallCount(),
			row.getInputTokens(),
			row.getOutputTokens(),
			row.getReasoningTokens()
		)).toList();
		return new AdminAiUsageUserListResponse(users);
	}

	private DateRange dateRange(LocalDate from, LocalDate to) {
		LocalDate effectiveTo = to == null
			? LocalDate.now(clock.withZone(SEOUL))
			: to;
		LocalDate effectiveFrom = from == null
			? effectiveTo.minusDays(6)
			: from;
		if (effectiveFrom.isAfter(effectiveTo)
			|| ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1
				> MAX_RANGE_DAYS) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return new DateRange(
			effectiveFrom.atStartOfDay(SEOUL)
				.withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime(),
			effectiveTo.plusDays(1).atStartOfDay(SEOUL)
				.withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime()
		);
	}

	private record DateRange(
		LocalDateTime fromUtc,
		LocalDateTime toExclusiveUtc
	) {
	}
}
