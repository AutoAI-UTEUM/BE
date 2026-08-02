package io.edupilot.exam.dto;

import java.math.BigDecimal;

import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.SubmissionStatus;

public record ExamSubmissionSummaryResponse(
	Long submissionId,
	int attemptNo,
	SubmissionStatus status,
	BigDecimal score,
	BigDecimal maxScore,
	BigDecimal normalizedScore
) {
	public static ExamSubmissionSummaryResponse from(ExamSubmission submission) {
		return new ExamSubmissionSummaryResponse(
			submission.getId(), submission.getAttemptNo(), submission.getStatus(),
			submission.getScore(), submission.getMaxScore(), submission.getNormalizedScore()
		);
	}
}
