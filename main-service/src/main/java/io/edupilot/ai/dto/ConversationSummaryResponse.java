package io.edupilot.ai.dto;

public record ConversationSummaryResponse(
	String schemaVersion,
	String summary,
	AiUsage usage
) {
}
