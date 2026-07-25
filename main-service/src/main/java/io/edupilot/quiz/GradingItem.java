package io.edupilot.quiz;

import java.math.BigDecimal;

public record GradingItem(
	String questionId,
	BigDecimal score,
	BigDecimal maxScore,
	GradingVerdict verdict,
	String feedback
) {
}
