package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicQuizQuestion(
	String questionId,
	String questionText,
	BigDecimal maxScore,
	List<QuizOption> options
) {
}
