package io.edupilot.exam.dto;

import java.time.Instant;

import io.edupilot.global.response.ApiError;

public record ExamAttemptDraftConflictResponse(
	boolean success,
	ApiError error,
	ExamAttemptDraftResponse latestDraft,
	String traceId,
	Instant timestamp
) {
}
