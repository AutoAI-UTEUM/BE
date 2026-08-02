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
	public static ExamAnswerResultResponse from(ExamAnswer answer) {
		return new ExamAnswerResultResponse(
			"q" + answer.getQuestionNo(), answer.getAnswer(), answer.getScore(),
			answer.getMaxScore(), answer.getVerdict(), answer.getFeedback()
		);
	}
}
