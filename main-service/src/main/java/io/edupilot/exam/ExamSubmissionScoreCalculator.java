package io.edupilot.exam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class ExamSubmissionScoreCalculator {

	public Result calculate(
		ExamSubmission submission,
		List<ExamAnswer> answers
	) {
		BigDecimal score = BigDecimal.ZERO;
		for (ExamAnswer answer : answers) {
			BigDecimal effectiveScore = answer.effectiveScore();
			if (effectiveScore == null) {
				throw new BusinessException(ErrorCode.GRADING_RESULT_INVALID);
			}
			score = score.add(effectiveScore);
		}
		BigDecimal maxScore = submission.getMaxScore();
		if (maxScore == null || maxScore.signum() <= 0) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		BigDecimal normalizedScore = score.multiply(BigDecimal.valueOf(100))
			.divide(maxScore, 2, RoundingMode.HALF_UP);
		return new Result(score, normalizedScore);
	}

	public record Result(BigDecimal score, BigDecimal normalizedScore) {
	}
}
