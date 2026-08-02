package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamQuestionType;
import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.RubricCriterion;

public record InstructorExamQuestionResponse(
	String questionId,
	ExamQuestionType questionType,
	String questionText,
	BigDecimal maxScore,
	List<QuizOption> options,
	String correctAnswer,
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
			question.getPublicQuestion().options(),
			question.getQuestionType() == ExamQuestionType.MCQ
				|| question.getQuestionType() == ExamQuestionType.OX
				? privateAnswer.correctAnswer() : null,
			privateAnswer.explanation(),
			question.getQuestionType() == ExamQuestionType.SHORT
				? privateAnswer.correctAnswer() : null,
			privateAnswer.modelAnswer(),
			privateAnswer.rubric()
		);
	}
}
