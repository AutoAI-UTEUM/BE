package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamQuestionType;
import io.edupilot.quiz.QuizOption;

public record StudentExamQuestionResponse(
	String questionId,
	String questionText,
	BigDecimal maxScore,
	ExamQuestionType questionType,
	List<QuizOption> options
) {
	public static StudentExamQuestionResponse from(ExamQuestion question) {
		return new StudentExamQuestionResponse(
			"q" + question.getQuestionNo(),
			question.getPublicQuestion().question(),
			question.getPoints(),
			question.getQuestionType(),
			question.getPublicQuestion().options()
		);
	}
}
