package io.edupilot.exam.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualScoreAdjustmentRequest(
	@NotNull @Digits(integer = 8, fraction = 2) BigDecimal score,
	@Size(max = MAX_REASON_LENGTH)
	@Schema(description = "선택 변경 사유. 소유 강사에게만 반환하며 생략·null·공백은 사유 없음입니다.",
		maxLength = MAX_REASON_LENGTH, nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
	String reason
) {
	public static final int MAX_REASON_LENGTH = 500;

	public ManualScoreAdjustmentRequest(BigDecimal score) {
		this(score, null);
	}
}
