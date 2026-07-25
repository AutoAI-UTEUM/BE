package io.edupilot.quiz.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record QuizSubmitRequest(
	String requestId,
	List<Answer> answers
) {

	public record Answer(
		String questionId,
		JsonNode answer
	) {
	}
}
