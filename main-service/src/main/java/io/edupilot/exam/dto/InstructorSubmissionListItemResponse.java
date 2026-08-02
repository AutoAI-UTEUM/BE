package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.SubmissionStatus;

public record InstructorSubmissionListItemResponse(
	Long submissionId,
	Long userId,
	String userName,
	int attemptNo,
	long attemptCount,
	SubmissionStatus status,
	BigDecimal score,
	BigDecimal maxScore,
	BigDecimal normalizedScore,
	Instant submittedAt,
	Instant gradedAt
) {
	public static InstructorSubmissionListItemResponse from(
		ExamSubmission submission,
		long attemptCount
	) {
		return new InstructorSubmissionListItemResponse(
			submission.getId(), submission.getUserId(), submission.getUserName(),
			submission.getAttemptNo(), attemptCount, submission.getStatus(),
			submission.getScore(), submission.getMaxScore(),
			submission.getNormalizedScore(), submission.getSubmittedAt(), submission.getGradedAt()
		);
	}
}
