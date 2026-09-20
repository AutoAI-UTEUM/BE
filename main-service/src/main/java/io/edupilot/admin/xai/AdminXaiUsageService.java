package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.xai.dto.AdminXaiUsagePointResponse;
import io.edupilot.admin.xai.dto.AdminXaiUsageResponse;
import io.edupilot.admin.xai.dto.XaiUsageGranularity;
import io.edupilot.admin.xai.dto.XaiUsageGroupBy;
import io.edupilot.admin.xai.dto.XaiUsageMetric;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.aiusage.AiUsageLogRepository.XaiCostSummaryProjection;
import io.edupilot.aiusage.AiUsageLogRepository.XaiUsageProjection;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class AdminXaiUsageService {

	static final BigDecimal TICKS_PER_USD = new BigDecimal("10000000000");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final AiUsageLogRepository repository;

	public AdminXaiUsageService(AiUsageLogRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public AdminXaiUsageResponse usage(
		LocalDate from,
		LocalDate to,
		XaiUsageGranularity granularity,
		XaiUsageMetric metric,
		XaiUsageGroupBy groupBy
	) {
		DateRange range = dateRange(from, to);
		if (groupBy == XaiUsageGroupBy.API_KEY) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_GROUP_BY);
		}
		List<XaiUsageProjection> rows = groupBy == XaiUsageGroupBy.MODEL
			? repository.aggregateXaiUsageByModel(
				range.fromUtc(),
				range.toExclusiveUtc()
			)
			: repository.aggregateXaiUsageByFeature(
				range.fromUtc(),
				range.toExclusiveUtc()
			);
		long unknownCostCalls = rows.stream()
			.map(XaiUsageProjection::getUnknownCostCalls)
			.filter(java.util.Objects::nonNull)
			.mapToLong(Long::longValue)
			.sum();
		List<AdminXaiUsagePointResponse> items = rows.stream()
			.map(row -> point(row, metric))
			.toList();
		return new AdminXaiUsageResponse(
			from,
			to,
			granularity,
			metric,
			groupBy,
			unknownCostCalls,
			items
		);
	}

	@Transactional(readOnly = true)
	public CostSummary costSummary(LocalDate from, LocalDate to) {
		DateRange range = dateRange(from, to);
		return costSummary(range.fromUtc(), range.toExclusiveUtc());
	}

	@Transactional(readOnly = true)
	public CostSummary costSummary(Instant from, Instant toExclusive) {
		if (from == null || toExclusive == null || !from.isBefore(toExclusive)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return costSummary(
			LocalDateTime.ofInstant(from, ZoneOffset.UTC),
			LocalDateTime.ofInstant(toExclusive, ZoneOffset.UTC)
		);
	}

	private CostSummary costSummary(
		LocalDateTime fromUtc,
		LocalDateTime toExclusiveUtc
	) {
		XaiCostSummaryProjection row = repository.summarizeXaiCost(
			fromUtc,
			toExclusiveUtc
		);
		if (row == null) {
			return new CostSummary(BigDecimal.ZERO, 0, 0);
		}
		long knownCostCalls = nullToZero(row.getKnownCostCalls());
		long unknownCostCalls = nullToZero(row.getUnknownCostCalls());
		BigDecimal costUsd = toUsd(row.getCostUsdTicks());
		if (costUsd == null && knownCostCalls == 0 && unknownCostCalls == 0) {
			costUsd = BigDecimal.ZERO;
		}
		return new CostSummary(
			costUsd,
			knownCostCalls,
			unknownCostCalls
		);
	}

	private AdminXaiUsagePointResponse point(
		XaiUsageProjection row,
		XaiUsageMetric metric
	) {
		return new AdminXaiUsagePointResponse(
			row.getUsageDate(),
			row.getGroupKey(),
			metric == XaiUsageMetric.COST
				? toUsd(row.getCostUsdTicks())
				: null,
			metric == XaiUsageMetric.TOKENS ? row.getTokenCount() : null,
			metric == XaiUsageMetric.CALLS ? row.getCallCount() : null
		);
	}

	private DateRange dateRange(LocalDate from, LocalDate to) {
		if (from == null || to == null || from.isAfter(to)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return new DateRange(
			from.atStartOfDay(SEOUL)
				.withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime(),
			to.plusDays(1).atStartOfDay(SEOUL)
				.withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime()
		);
	}

	static BigDecimal toUsd(BigDecimal ticks) {
		return ticks == null
			? null
			: ticks.divide(TICKS_PER_USD, 10, RoundingMode.UNNECESSARY)
				.stripTrailingZeros();
	}

	private long nullToZero(Long value) {
		return value == null ? 0 : value;
	}

	public record CostSummary(
		BigDecimal costUsd,
		long knownCostCalls,
		long unknownCostCalls
	) {
	}

	private record DateRange(
		LocalDateTime fromUtc,
		LocalDateTime toExclusiveUtc
	) {
	}
}
