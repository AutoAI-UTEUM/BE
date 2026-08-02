package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamStatus;

public record InstructorExamListItemResponse(
	Long examId,
	String title,
	Integer weekNumber,
	ExamStatus status,
	boolean allowRetake,
	BigDecimal totalScore,
	long submissionCount,
	Instant publishedAt,
	Instant closedAt
) {
	public static InstructorExamListItemResponse from(Exam exam, long submissionCount) {
		return new InstructorExamListItemResponse(
			exam.getId(), exam.getTitle(), exam.getWeekNumber(), exam.getStatus(),
			exam.isAllowRetake(), exam.getTotalScore(), submissionCount,
			exam.getPublishedAt(), exam.getClosedAt()
		);
	}
}
