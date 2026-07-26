package io.edupilot.quiz.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.UiAction;

public record QuizSubmitResponse(
	Long submissionId,
	Long quizId,
	QuizType quizType,
	BigDecimal score,
	BigDecimal maxScore,
	boolean passed,
	QuizGradingResultResponse gradingResult,
	List<UiAction> uiActions
) {

	public static QuizSubmitResponse from(
		QuizSubmission submission,
		List<UiAction> uiActions
	) {
		return new QuizSubmitResponse(
			submission.getId(),
			submission.getQuizId(),
			submission.getQuizType(),
			submission.getScore(),
			submission.getMaxScore(),
			submission.isPassed(),
			QuizGradingResultResponse.from(submission.getGradingResult()),
			List.copyOf(uiActions)
		);
	}

	public QuizSubmitResponse withUiActions(List<UiAction> nextUiActions) {
		return new QuizSubmitResponse(
			submissionId,
			quizId,
			quizType,
			score,
			maxScore,
			passed,
			gradingResult,
			List.copyOf(nextUiActions)
		);
	}
}
