package io.edupilot.exam.dto;

import java.math.BigDecimal;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.Verdict;

public record ExamAnswerResultResponse(
	String questionId,
	String answer,
	BigDecimal score,
	BigDecimal maxScore,
	Verdict verdict,
	String feedback
) {
	public static ExamAnswerResultResponse from(ExamAnswer answer, boolean revealResult) {
		return new ExamAnswerResultResponse(
			"q" + answer.getQuestionNo(), answer.getAnswer(),
			revealResult ? answer.getScore() : null,
			answer.getMaxScore(),
			revealResult ? answer.getVerdict() : null,
			revealResult ? answer.getFeedback() : null
		);
	}
}
