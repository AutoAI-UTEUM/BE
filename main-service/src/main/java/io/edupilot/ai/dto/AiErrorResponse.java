package io.edupilot.ai.dto;

public record AiErrorResponse(
	String schemaVersion,
	AiError error,
	String traceId
) {

	public record AiError(
		String code,
		Category category,
		String message,
		boolean retryable
	) {
	}

	public enum Category {
		TIMEOUT,
		SCHEMA,
		POLICY,
		INTERNAL,
		AUTH
	}
}
