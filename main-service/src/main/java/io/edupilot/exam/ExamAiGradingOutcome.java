package io.edupilot.exam;

import java.math.BigDecimal;
import java.util.Map;
import io.edupilot.guardian.GuardianConsentFence;

public record ExamAiGradingOutcome(
	Map<String, GradedItem> grades,
	boolean failed,
	GuardianConsentFence.Snapshot consent
) {
	public record GradedItem(
		BigDecimal score,
		Verdict verdict,
		String feedback
	) {
	}
}
