package io.edupilot.session.dto;

import java.time.Instant;

public record ConversationStartResponse(
	String conversationId,
	Instant startedAt
) {

	public static ConversationStartResponse of(
		int conversationNumber,
		Instant startedAt
	) {
		return new ConversationStartResponse(
			"conversation-" + conversationNumber,
			startedAt
		);
	}
}
