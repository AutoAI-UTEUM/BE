package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.List;

public record GradingResult(
	String schemaVersion,
	BigDecimal score,
	BigDecimal maxScore,
	List<GradingItem> items
) {
}
