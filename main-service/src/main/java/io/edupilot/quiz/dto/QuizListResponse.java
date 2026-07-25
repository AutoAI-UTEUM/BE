package io.edupilot.quiz.dto;

import java.util.List;

public record QuizListResponse(
	List<QuizSummaryResponse> quizzes
) {
}
