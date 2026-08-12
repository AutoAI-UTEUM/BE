package io.edupilot.quiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.quiz.GradingVerdict;

public record QuizSubmissionDetailResponse(
	Long quizId,
	Long submissionId,
	Instant submittedAt,
	BigDecimal score,
	BigDecimal maxScore,
	boolean passed,
	List<Item> items
) {

	public record Item(
		String questionId,
		String submittedAnswer,
		String correctAnswer,
		GradingVerdict verdict,
		BigDecimal score,
		BigDecimal maxScore,
		String feedback,
		String explanation
	) {
	}
}
