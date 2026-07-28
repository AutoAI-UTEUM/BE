package io.edupilot.quiz.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.quiz.Quiz;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizType;

public record QuizSummaryResponse(
	Long quizId,
	String title,
	QuizType quizType,
	int page,
	int coverageStartPage,
	int coverageEndPage,
	int questionCount,
	boolean submitted,
	BigDecimal score,
	BigDecimal maxScore,
	Boolean passed,
	Instant createdAt
) {

	public static QuizSummaryResponse from(
		Quiz quiz,
		QuizSubmission submission
	) {
		return new QuizSummaryResponse(
			quiz.getId(),
			quiz.getTitle(),
			quiz.getQuizType(),
			quiz.getPageNumber(),
			quiz.getCoverageStartPage(),
			quiz.getCoverageEndPage(),
			quiz.getPublicQuestions().size(),
			submission != null,
			submission == null ? null : submission.getScore(),
			submission == null ? null : submission.getMaxScore(),
			submission == null ? null : submission.isPassed(),
			quiz.getCreatedAt()
		);
	}
}
