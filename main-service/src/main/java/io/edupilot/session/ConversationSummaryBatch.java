package io.edupilot.session;

import java.time.Instant;
import java.util.List;

import io.edupilot.ai.dto.ConversationSummaryMessage;

public record ConversationSummaryBatch(
	Long sessionId,
	String previousSummary,
	Long previousLastSummarizedMessageId,
	Instant conversationResetAt,
	Long summarizedThroughMessageId,
	List<ConversationSummaryMessage> messages,
	int characterCount
) {
}
