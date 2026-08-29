package io.edupilot.aiusage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {

	long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);

	// Token SUMs intentionally keep SQL null semantics: null samples are excluded,
	// and the aggregate remains null when every sample in the group is null.
	@Query(value = """
		select date(convert_tz(created_at, '+00:00', '+09:00')) as usageDate,
		       count(*) as callCount,
		       sum(case when success = true then 1 else 0 end) as successCount,
		       sum(case when success = false then 1 else 0 end) as failCount,
		       sum(input_tokens) as inputTokens,
		       sum(output_tokens) as outputTokens,
		       sum(reasoning_tokens) as reasoningTokens
		from ai_usage_log
		where created_at >= :from
		  and created_at < :toExclusive
		group by date(convert_tz(created_at, '+00:00', '+09:00'))
		order by usageDate
		""", nativeQuery = true)
	List<DailyUsageProjection> aggregateDaily(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive
	);

	@Query(value = """
		select feature as feature,
		       count(*) as callCount,
		       sum(input_tokens) as inputTokens,
		       sum(output_tokens) as outputTokens,
		       sum(reasoning_tokens) as reasoningTokens
		from ai_usage_log
		where created_at >= :from
		  and created_at < :toExclusive
		group by feature
		order by feature
		""", nativeQuery = true)
	List<FeatureUsageProjection> aggregateByFeature(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive
	);

	@Query(value = """
		select usage_log.user_id as userId,
		       account.email as email,
		       account.name as name,
		       account.status as status,
		       count(*) as callCount,
		       sum(usage_log.input_tokens) as inputTokens,
		       sum(usage_log.output_tokens) as outputTokens,
		       sum(usage_log.reasoning_tokens) as reasoningTokens
		from ai_usage_log usage_log
		join users account on account.id = usage_log.user_id
		where usage_log.created_at >= :from
		  and usage_log.created_at < :toExclusive
		group by usage_log.user_id, account.email, account.name, account.status
		order by callCount desc, usage_log.user_id
		limit :limit
		""", nativeQuery = true)
	List<UserUsageProjection> aggregateByUser(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive,
		@Param("limit") int limit
	);

	interface DailyUsageProjection {
		LocalDate getUsageDate();
		Long getCallCount();
		Long getSuccessCount();
		Long getFailCount();
		Long getInputTokens();
		Long getOutputTokens();
		Long getReasoningTokens();
	}

	interface FeatureUsageProjection {
		String getFeature();
		Long getCallCount();
		Long getInputTokens();
		Long getOutputTokens();
		Long getReasoningTokens();
	}

	interface UserUsageProjection {
		Long getUserId();
		String getEmail();
		String getName();
		String getStatus();
		Long getCallCount();
		Long getInputTokens();
		Long getOutputTokens();
		Long getReasoningTokens();
	}
}
