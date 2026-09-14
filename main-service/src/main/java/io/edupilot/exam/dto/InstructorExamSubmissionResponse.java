package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.exam.ExamAnswer;
import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.SubmissionStatus;

public record InstructorExamSubmissionResponse(
	Long submissionId,
	int attemptNo,
	SubmissionStatus status,
	BigDecimal score,
	BigDecimal maxScore,
	BigDecimal normalizedScore,
	Instant submittedAt,
	Instant gradedAt,
	List<InstructorExamAnswerResultResponse> items
) {
	public static InstructorExamSubmissionResponse from(
		ExamSubmission submission,
		List<ExamAnswer> answers
	) {
		boolean revealResult = submission.getStatus() != SubmissionStatus.SUBMITTED;
		return new InstructorExamSubmissionResponse(
			submission.getId(), submission.getAttemptNo(), submission.getStatus(),
			submission.getScore(), submission.getMaxScore(), submission.getNormalizedScore(),
			submission.getSubmittedAt(), submission.getGradedAt(),
			answers.stream()
				.map(answer -> InstructorExamAnswerResultResponse.from(answer, revealResult))
				.toList()
		);
	}
}
