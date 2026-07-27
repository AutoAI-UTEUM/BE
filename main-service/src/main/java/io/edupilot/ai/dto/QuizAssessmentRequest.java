package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuizAssessmentRequest(
	String schemaVersion,
	QuizResult quizResult,
	List<QuizItem> quizItems,
	List<StudentAnswer> studentAnswers,
	PageContext pageContext,
	String learnerMemoryDigest
) {
	public record QuizResult(
		Long quizId,
		String quizType,
		BigDecimal score,
		BigDecimal maxScore,
		boolean passed,
		List<ResultItem> items
	) {
	}

	public record ResultItem(
		String questionId,
		BigDecimal score,
		BigDecimal maxScore,
		String verdict,
		String feedback
	) {
	}

	public record QuizItem(
		String questionId,
		String question,
		String modelAnswer,
		BigDecimal maxScore
	) {
	}

	public record StudentAnswer(
		String questionId,
		String answer
	) {
	}

	public record PageContext(
		int coverageStartPage,
		int coverageEndPage,
		String text
	) {
	}
}
