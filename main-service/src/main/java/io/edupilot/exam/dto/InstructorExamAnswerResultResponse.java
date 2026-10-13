package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

public record InstructorExamAnswerResultResponse(
	String questionId,
	String answer,
	BigDecimal score,
	BigDecimal maxScore,
	Verdict verdict,
	String feedback,
	BigDecimal manualScore,
	@Schema(description = "현재 수동 점수의 선택 변경 사유. 소유 강사 전용이며 사유가 없으면 null입니다.",
		maxLength = 500, nullable = true)
	String manualScoreReason,
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
			answer.getManualScoreReason(),
			answer.getAdjustedAt()
		);
	}
}
