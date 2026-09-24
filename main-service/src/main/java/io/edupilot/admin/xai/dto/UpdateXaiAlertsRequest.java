package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateXaiAlertsRequest(
	@NotNull
	@DecimalMin(value = "0", inclusive = false)
	@Digits(integer = 15, fraction = 4)
	BigDecimal balanceCriticalUsd,

	@NotNull
	@DecimalMin(value = "0", inclusive = false)
	@Digits(integer = 15, fraction = 4)
	BigDecimal balanceWarningUsd,

	@NotNull
	@Min(1)
	Integer depletionCriticalDays,

	@NotNull
	@Min(1)
	Integer depletionWarningDays
) {
}
