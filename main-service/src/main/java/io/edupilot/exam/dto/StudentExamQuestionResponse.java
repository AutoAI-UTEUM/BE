package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamQuestionType;

public record StudentExamQuestionResponse(
	String questionId,
	String questionText,
	BigDecimal maxScore,
	ExamQuestionType questionType,
	List<ExamOptionResponse> options
) {
	public static StudentExamQuestionResponse from(ExamQuestion question) {
		return new StudentExamQuestionResponse(
			"q" + question.getQuestionNo(),
			question.getPublicQuestion().question(),
			question.getPoints(),
			question.getQuestionType(),
			question.getPublicQuestion().options().stream()
				.map(ExamOptionResponse::from).toList()
		);
	}
}
