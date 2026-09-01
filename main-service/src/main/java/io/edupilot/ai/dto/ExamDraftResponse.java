package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.edupilot.exam.ExamQuestionType;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

public record ExamDraftResponse(
	String schemaVersion,
	Long examId,
	List<Question> questions,
	AiUsage usage
) {

	@JsonTypeInfo(
		use = JsonTypeInfo.Id.NAME,
		include = JsonTypeInfo.As.EXISTING_PROPERTY,
		property = "questionType",
		visible = true
	)
	@JsonSubTypes({
		@JsonSubTypes.Type(value = McqQuestion.class, name = "MCQ"),
		@JsonSubTypes.Type(value = OxQuestion.class, name = "OX"),
		@JsonSubTypes.Type(value = ShortQuestion.class, name = "SHORT"),
		@JsonSubTypes.Type(value = EssayQuestion.class, name = "ESSAY")
	})
	@Schema(
		discriminatorProperty = "questionType",
		oneOf = {McqQuestion.class, OxQuestion.class, ShortQuestion.class, EssayQuestion.class},
		discriminatorMapping = {
			@DiscriminatorMapping(value = "MCQ", schema = McqQuestion.class),
			@DiscriminatorMapping(value = "OX", schema = OxQuestion.class),
			@DiscriminatorMapping(value = "SHORT", schema = ShortQuestion.class),
			@DiscriminatorMapping(value = "ESSAY", schema = EssayQuestion.class)
		}
	)
	public sealed interface Question permits
		McqQuestion, OxQuestion, ShortQuestion, EssayQuestion {

		ExamQuestionType questionType();

		Integer sourcePageNumber();

		String questionId();

		String questionText();

		BigDecimal points();
	}

	public record McqQuestion(
		ExamQuestionType questionType,
		Integer sourcePageNumber,
		String questionId,
		String questionText,
		BigDecimal points,
		List<Choice> choices,
		String answerChoiceId,
		String explanation
	) implements Question {
	}

	public record OxQuestion(
		ExamQuestionType questionType,
		Integer sourcePageNumber,
		String questionId,
		String questionText,
		BigDecimal points,
		Boolean answerValue,
		String explanation
	) implements Question {
	}

	public record ShortQuestion(
		ExamQuestionType questionType,
		Integer sourcePageNumber,
		String questionId,
		String questionText,
		BigDecimal points,
		String referenceAnswer,
		List<String> gradingCriteria
	) implements Question {
	}

	public record EssayQuestion(
		ExamQuestionType questionType,
		Integer sourcePageNumber,
		String questionId,
		String questionText,
		BigDecimal points,
		String modelAnswer,
		List<Rubric> rubric
	) implements Question {
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
