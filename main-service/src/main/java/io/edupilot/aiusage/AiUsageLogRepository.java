package io.edupilot.aiusage;

import java.math.BigDecimal;
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

	@Query(value = """
		select date(convert_tz(created_at, '+00:00', '+09:00')) as usageDate,
		       feature as groupKey,
		       count(*) as callCount,
		       sum(coalesce(input_tokens, 0)
		           + coalesce(output_tokens, 0)
		           + coalesce(reasoning_tokens, 0)) as tokenCount,
		       sum(cost_usd_ticks) as costUsdTicks,
		       sum(case when cost_usd_ticks is null then 1 else 0 end)
		           as unknownCostCalls
		from ai_usage_log
		where created_at >= :from
		  and created_at < :toExclusive
		group by date(convert_tz(created_at, '+00:00', '+09:00')), feature
		order by usageDate, groupKey
		""", nativeQuery = true)
	List<XaiUsageProjection> aggregateXaiUsageByFeature(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive
	);

	@Query(value = """
		select date(convert_tz(created_at, '+00:00', '+09:00')) as usageDate,
		       coalesce(model, 'UNKNOWN') as groupKey,
		       count(*) as callCount,
		       sum(coalesce(input_tokens, 0)
		           + coalesce(output_tokens, 0)
		           + coalesce(reasoning_tokens, 0)) as tokenCount,
		       sum(cost_usd_ticks) as costUsdTicks,
		       sum(case when cost_usd_ticks is null then 1 else 0 end)
		           as unknownCostCalls
		from ai_usage_log
		where created_at >= :from
		  and created_at < :toExclusive
		group by date(convert_tz(created_at, '+00:00', '+09:00')),
		         coalesce(model, 'UNKNOWN')
		order by usageDate, groupKey
		""", nativeQuery = true)
	List<XaiUsageProjection> aggregateXaiUsageByModel(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive
	);

	@Query(value = """
		select sum(cost_usd_ticks) as costUsdTicks,
		       sum(case when cost_usd_ticks is not null then 1 else 0 end)
		           as knownCostCalls,
		       sum(case when cost_usd_ticks is null then 1 else 0 end)
		           as unknownCostCalls
		from ai_usage_log
		where created_at >= :from
		  and created_at < :toExclusive
		""", nativeQuery = true)
	XaiCostSummaryProjection summarizeXaiCost(
		@Param("from") LocalDateTime from,
		@Param("toExclusive") LocalDateTime toExclusive
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

	interface XaiUsageProjection {
		LocalDate getUsageDate();
		String getGroupKey();
		Long getCallCount();
		Long getTokenCount();
		BigDecimal getCostUsdTicks();
		Long getUnknownCostCalls();
	}

	interface XaiCostSummaryProjection {
		BigDecimal getCostUsdTicks();
		Long getKnownCostCalls();
		Long getUnknownCostCalls();
	}
}
