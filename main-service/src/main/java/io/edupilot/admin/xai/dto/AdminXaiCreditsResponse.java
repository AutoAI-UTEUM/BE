package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiCreditsResponse(
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal prepaidBalanceUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidLimitUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidUsedUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal postpaidRemainingUsd,
	Instant fetchedAt,
	Instant lastSuccessfulSyncAt,
	Boolean stale,
	boolean available
) {

	public static AdminXaiCreditsResponse unavailable() {
		return new AdminXaiCreditsResponse(
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
