package io.edupilot.quiz;

import java.math.BigDecimal;

public record RubricCriterion(
	String criterion,
	BigDecimal weight
) {
}
