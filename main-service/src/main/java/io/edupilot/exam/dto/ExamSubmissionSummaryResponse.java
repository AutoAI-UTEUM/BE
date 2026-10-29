package io.edupilot.exam.dto;

import java.math.BigDecimal;

import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.ExamReviewPolicy;
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
		boolean revealResult = ExamReviewPolicy.isStudentResultAvailable(submission);
		return new ExamSubmissionSummaryResponse(
			submission.getId(), submission.getAttemptNo(), submission.getStatus(),
			revealResult ? submission.getScore() : null, submission.getMaxScore(),
			revealResult ? submission.getNormalizedScore() : null
		);
	}
}
