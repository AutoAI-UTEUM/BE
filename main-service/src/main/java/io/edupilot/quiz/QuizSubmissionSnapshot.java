package io.edupilot.quiz;

import java.math.BigDecimal;

public record QuizSubmissionSnapshot(
	Long submissionId,
	Long quizId,
	Long userId,
	QuizType quizType,
	BigDecimal score,
	BigDecimal maxScore,
	boolean passed
) {

	public static QuizSubmissionSnapshot from(QuizSubmission submission) {
		return new QuizSubmissionSnapshot(
			submission.getId(),
			submission.getQuizId(),
			submission.getUserId(),
			submission.getQuizType(),
			submission.getScore(),
			submission.getMaxScore(),
			submission.isPassed()
		);
	}
}
