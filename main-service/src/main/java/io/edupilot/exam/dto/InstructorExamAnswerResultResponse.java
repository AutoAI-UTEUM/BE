package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.Verdict;

public record InstructorExamAnswerResultResponse(
	String questionId,
	String answer,
	BigDecimal score,
	BigDecimal maxScore,
	Verdict verdict,
	String feedback,
	BigDecimal manualScore,
	Instant adjustedAt
) {
	public static InstructorExamAnswerResultResponse from(
		ExamAnswer answer,
		boolean revealResult
	) {
		return new InstructorExamAnswerResultResponse(
			"q" + answer.getQuestionNo(),
			answer.getAnswer(),
			revealResult ? answer.effectiveScore() : null,
			answer.getMaxScore(),
			revealResult ? answer.getVerdict() : null,
			revealResult ? answer.getFeedback() : null,
			answer.getManualScore(),
			answer.getAdjustedAt()
		);
	}
}
