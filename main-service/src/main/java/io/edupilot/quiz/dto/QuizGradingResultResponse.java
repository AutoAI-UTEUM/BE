package io.edupilot.quiz.dto;

import java.util.List;

import io.edupilot.quiz.GradingItem;
import io.edupilot.quiz.GradingResult;

public record QuizGradingResultResponse(
	List<GradingItem> items
) {

	public static QuizGradingResultResponse from(GradingResult result) {
		return new QuizGradingResultResponse(List.copyOf(result.items()));
	}
}
