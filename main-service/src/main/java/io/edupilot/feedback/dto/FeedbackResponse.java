package io.edupilot.feedback.dto;

import java.time.Instant;

import io.edupilot.feedback.Feedback;

public record FeedbackResponse(
	Long feedbackId,
	Instant createdAt
) {
	public static FeedbackResponse from(Feedback feedback) {
		return new FeedbackResponse(feedback.getId(), feedback.getCreatedAt());
	}
}
