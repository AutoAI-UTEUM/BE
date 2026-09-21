package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiOverviewResponse(
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal prepaidBalanceUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal prepaidUsedThisPeriodUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal prepaidAvailableUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal currentMonthCostUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidLimitUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidUsedUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidRemainingUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal totalAvailableUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal averageDailyCost7d,
	XaiCostSource costSource,
	Instant projectedDepletionAt,
	XaiRiskLevel riskLevel,
	Instant fetchedAt,
	Instant lastSuccessfulSyncAt,
	Boolean stale,
	boolean available
) {

	public static AdminXaiOverviewResponse unavailable() {
		return new AdminXaiOverviewResponse(
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			false
		);
	}
}
