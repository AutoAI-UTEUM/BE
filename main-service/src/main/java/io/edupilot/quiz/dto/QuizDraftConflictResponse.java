package io.edupilot.quiz.dto;

import java.time.Instant;

import io.edupilot.global.response.ApiError;

public record QuizDraftConflictResponse(
	boolean success,
	ApiError error,
	QuizDraftResponse latestDraft,
	String traceId,
	Instant timestamp
) {
}
