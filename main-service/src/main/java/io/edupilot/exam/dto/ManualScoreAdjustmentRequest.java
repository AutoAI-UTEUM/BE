package io.edupilot.exam.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record ManualScoreAdjustmentRequest(
	@NotNull @Digits(integer = 8, fraction = 2) BigDecimal score
) {
}
