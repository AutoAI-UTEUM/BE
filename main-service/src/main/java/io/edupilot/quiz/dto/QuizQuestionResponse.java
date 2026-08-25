package io.edupilot.quiz.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.edupilot.quiz.PublicQuizQuestion;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuizQuestionResponse(
	String questionId,
	String questionText,
	BigDecimal maxScore,
	List<Option> options
) {

	public static QuizQuestionResponse from(PublicQuizQuestion question) {
		List<Option> options = question.choices() == null
			? null
			: question.choices().stream()
				.map(choice -> new Option(choice.choiceId(), choice.text()))
				.toList();
		return new QuizQuestionResponse(
			question.questionId(),
			question.questionText(),
			question.points(),
			options
		);
	}

	public record Option(
		String optionId,
		String text
	) {
	}
}
