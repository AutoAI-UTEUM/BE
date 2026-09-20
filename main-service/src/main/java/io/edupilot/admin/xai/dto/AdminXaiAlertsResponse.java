package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiAlertsResponse(
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal balanceCriticalUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal balanceWarningUsd,
	int depletionCriticalDays,
	int depletionWarningDays,
	Long updatedBy,
	Instant updatedAt
) {
}
