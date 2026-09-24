package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiReconciliationResponse(
	LocalDate requestedFrom,
	LocalDate requestedTo,
	LocalDate xaiPeriodFrom,
	LocalDate xaiPeriodTo,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal internalCostUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal xaiBilledUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal differenceUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal differenceRatio,
	long unknownCostCalls,
	String coverageNote,
	Boolean stale,
	boolean available
) {
}
