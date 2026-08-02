package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamStatus;

public record InstructorExamDetailResponse(
	Long examId,
	Long classroomId,
	String title,
	String description,
	Integer weekNumber,
	ExamStatus status,
	boolean allowRetake,
	BigDecimal totalScore,
	List<InstructorExamQuestionResponse> questions,
	Instant publishedAt,
	Instant closedAt,
	Instant createdAt,
	Instant updatedAt
) {
	public static InstructorExamDetailResponse from(
		Exam exam,
		List<ExamQuestion> questions
	) {
		return new InstructorExamDetailResponse(
			exam.getId(), exam.getClassroomId(), exam.getTitle(), exam.getDescription(),
			exam.getWeekNumber(), exam.getStatus(), exam.isAllowRetake(),
			exam.getTotalScore(),
			questions.stream().map(InstructorExamQuestionResponse::from).toList(),
			exam.getPublishedAt(), exam.getClosedAt(), exam.getCreatedAt(), exam.getUpdatedAt()
		);
	}
}
