package io.edupilot.quiz;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

@Component
public class DeterministicAnswerGrader {

	public Result grade(String expectedAnswer, String submittedAnswer, BigDecimal maxScore) {
		boolean correct = expectedAnswer != null && expectedAnswer.equals(submittedAnswer);
		return new Result(
			correct ? maxScore : BigDecimal.ZERO,
			correct ? GradingVerdict.CORRECT : GradingVerdict.WRONG
		);
	}

	public record Result(BigDecimal score, GradingVerdict verdict) {
	}
}
