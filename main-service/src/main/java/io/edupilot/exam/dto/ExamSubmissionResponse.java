package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.ExamReviewPolicy;
import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.SubmissionStatus;

public record ExamSubmissionResponse(
	Long submissionId,
	int attemptNo,
	SubmissionStatus status,
	BigDecimal score,
	BigDecimal maxScore,
	BigDecimal normalizedScore,
	Instant submittedAt,
	Instant gradedAt,
	List<ExamAnswerResultResponse> items
) {
	public static ExamSubmissionResponse from(
		ExamSubmission submission,
		List<ExamAnswer> answers
	) {
		boolean revealResult = ExamReviewPolicy.isStudentResultAvailable(submission);
		return new ExamSubmissionResponse(
			submission.getId(), submission.getAttemptNo(), submission.getStatus(),
			revealResult ? submission.getScore() : null, submission.getMaxScore(),
			revealResult ? submission.getNormalizedScore() : null,
			submission.getSubmittedAt(), revealResult ? submission.getGradedAt() : null,
			answers.stream()
				.map(answer -> ExamAnswerResultResponse.from(answer, revealResult))
				.toList()
		);
	}
}
