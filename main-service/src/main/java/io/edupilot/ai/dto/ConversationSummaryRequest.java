package io.edupilot.ai.dto;

import java.util.List;

public record ConversationSummaryRequest(
	String schemaVersion,
	String previousSummary,
	List<ConversationSummaryMessage> messages
) {
}
