package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.exam.ExamQuestionType;
import io.edupilot.quiz.RubricCriterion;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExamQuestionRequest(
	@NotNull ExamQuestionType questionType,
	@NotBlank String questionText,
	@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal points,
	List<ExamOptionRequest> options,
	String answerChoiceId,
	Boolean answerValue,
	String explanation,
	String referenceAnswer,
	String modelAnswer,
	List<RubricCriterion> rubric
) {
}
