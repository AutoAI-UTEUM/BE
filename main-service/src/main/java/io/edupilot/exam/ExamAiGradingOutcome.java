package io.edupilot.exam;

import java.math.BigDecimal;
import java.util.Map;

public record ExamAiGradingOutcome(
	Map<String, GradedItem> grades,
	boolean failed
) {
	public record GradedItem(
		BigDecimal score,
		Verdict verdict,
		String feedback
	) {
	}
}
