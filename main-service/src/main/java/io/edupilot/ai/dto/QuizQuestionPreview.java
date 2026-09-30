package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

// quiz_preview.py의 공개 필드만 중계한다. QuizGeneration.Question은 정답을 포함하므로 재사용하지 않는다.
public record QuizQuestionPreview(
	String type,
	String generationId,
	String quizType,
	String title,
	Coverage coverage,
	int questionIndex,
	int questionCount,
	boolean provisional,
	Question question
) {
	public record Coverage(int startPage, int endPage) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Question(
		String questionId,
		String questionText,
		BigDecimal points,
		List<Choice> choices
	) {
	}

	public record Choice(String choiceId, String text) {
	}
}
