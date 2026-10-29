package io.edupilot.quiz.dto;

import java.util.List;

public record QuizListResponse(
	List<QuizSummaryResponse> quizzes,
	int page,
	int size,
	long totalElements,
	int totalPages,
	boolean hasNext
) {
}
