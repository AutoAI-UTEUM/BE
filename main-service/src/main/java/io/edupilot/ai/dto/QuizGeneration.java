package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuizGeneration(
	String schemaVersion,
	String generationId,
	String quizType,
	Coverage coverage,
	String title,
	Integer questionCount,
	List<Question> questions
) {

	public record Coverage(
		Integer startPage,
		Integer endPage
	) {
	}

	public record Question(
		String questionId,
		String questionText,
		BigDecimal points,
		List<Choice> choices,
		String answerChoiceId,
		String explanation,
		Boolean answerValue,
		String referenceAnswer,
		List<String> gradingCriteria,
		String modelAnswer,
		List<Rubric> rubric
	) {
	}

	public record Choice(
		String choiceId,
		String text
	) {
	}

	public record Rubric(
		String criterion,
		BigDecimal weight
	) {
	}
}
