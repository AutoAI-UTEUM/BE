package io.edupilot.aiusage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRole;

@Service
public class AiQuotaService {

	private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

	private final AiUsageLogRepository repository;
	private final AiQuotaProperties properties;
	private final Clock clock;

	public AiQuotaService(
		AiUsageLogRepository repository,
		AiQuotaProperties properties,
		Clock clock
	) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public void checkQuota(Long userId, UserRole role) {
		if (!properties.enabled() || role == UserRole.ADMIN) {
			return;
		}
		Instant todayStart = LocalDate.now(clock.withZone(ZONE_SEOUL))
			.atStartOfDay(ZONE_SEOUL)
			.toInstant();
		// 성공과 실패 모두 실제 AI 호출 비용과 부하를 발생시키므로 함께 센다.
		long usageCount = repository
			.countByUserIdAndCreatedAtGreaterThanEqual(userId, todayStart);
		long dailyLimit = role == UserRole.INSTRUCTOR
			? properties.dailyInstructor()
			: properties.dailyDefault();
		if (usageCount >= dailyLimit) {
			throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED);
		}
	}
}
