package io.edupilot.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.exam.Exam;
import io.edupilot.exam.ExamQuestion;
import io.edupilot.exam.ExamStatus;

public record StudentExamDetailResponse(
	Long examId,
	Long classroomId,
	String title,
	String description,
	Integer weekNumber,
	ExamStatus status,
	boolean allowRetake,
	boolean submittable,
	BigDecimal totalScore,
	List<StudentExamQuestionResponse> questions,
	ExamSubmissionSummaryResponse latestSubmission,
	Instant publishedAt,
	Instant closedAt
) {
	public static StudentExamDetailResponse from(
		Exam exam,
		List<ExamQuestion> questions,
		boolean submittable,
		ExamSubmissionSummaryResponse latestSubmission
	) {
		return new StudentExamDetailResponse(
			exam.getId(), exam.getClassroomId(), exam.getTitle(), exam.getDescription(),
			exam.getWeekNumber(), exam.getStatus(), exam.isAllowRetake(), submittable,
			exam.getTotalScore(),
			questions.stream().map(StudentExamQuestionResponse::from).toList(),
			latestSubmission, exam.getPublishedAt(), exam.getClosedAt()
		);
	}
}
