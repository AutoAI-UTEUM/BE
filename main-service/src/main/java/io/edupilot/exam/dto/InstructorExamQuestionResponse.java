package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamQuestionType;
import io.edupilot.quiz.RubricCriterion;

public record InstructorExamQuestionResponse(
	String questionId,
	ExamQuestionType questionType,
	String questionText,
	BigDecimal maxScore,
	List<ExamOptionResponse> options,
	String answerChoiceId,
	Boolean answerValue,
	String explanation,
	String referenceAnswer,
	String modelAnswer,
	List<RubricCriterion> rubric
) {
	public static InstructorExamQuestionResponse from(ExamQuestion question) {
		var privateAnswer = question.getPrivateAnswer();
		return new InstructorExamQuestionResponse(
			"q" + question.getQuestionNo(),
			question.getQuestionType(),
			question.getPublicQuestion().question(),
			question.getPoints(),
			question.getPublicQuestion().options().stream()
				.map(ExamOptionResponse::from).toList(),
			privateAnswer.answerChoiceId(),
			privateAnswer.answerValue(),
			privateAnswer.explanation(),
			privateAnswer.referenceAnswer(),
			privateAnswer.modelAnswer(),
			privateAnswer.rubric()
		);
	}
}
