package io.edupilot.classroom.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.edupilot.quiz.QuizType;

public record ClassroomStudentQuizAnalyticsResponse(
	Long quizId,
	Long materialId,
	String materialTitle,
	Integer weekNumber,
	String title,
	QuizType quizType,
	int pageNumber,
	boolean submitted,
	BigDecimal score,
	BigDecimal maxScore,
	Boolean passed,
	Instant submittedAt
) {
}
