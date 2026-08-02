package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamStatus;

public record StudentExamListItemResponse(
	Long examId,
	String title,
	Integer weekNumber,
	ExamStatus status,
	boolean allowRetake,
	BigDecimal totalScore,
	ExamSubmissionSummaryResponse latestSubmission,
	Instant publishedAt,
	Instant closedAt
) {
	public static StudentExamListItemResponse from(
		Exam exam,
		ExamSubmissionSummaryResponse latestSubmission
	) {
		return new StudentExamListItemResponse(
			exam.getId(), exam.getTitle(), exam.getWeekNumber(), exam.getStatus(),
			exam.isAllowRetake(), exam.getTotalScore(), latestSubmission,
			exam.getPublishedAt(), exam.getClosedAt()
		);
	}
}
