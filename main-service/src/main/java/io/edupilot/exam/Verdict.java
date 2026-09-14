package io.edupilot.exam;

import java.math.BigDecimal;

public enum Verdict {
	CORRECT,
	PARTIAL,
	WRONG;

	public static Verdict fromScore(BigDecimal score, BigDecimal maxScore) {
		if (score.compareTo(maxScore) == 0) {
			return CORRECT;
		}
		if (score.signum() == 0) {
			return WRONG;
		}
		return PARTIAL;
	}
}
