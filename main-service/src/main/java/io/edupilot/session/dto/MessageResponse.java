package io.edupilot.session.dto;

import java.time.Instant;

import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageStatus;
import io.edupilot.session.MessageType;
import io.edupilot.session.SenderType;

public record MessageResponse(
	Long messageId,
	SenderType senderType,
	MessageType messageType,
	String content,
	int pageNumber,
	ChatMessageStatus status,
	Instant createdAt
) {
	public static MessageResponse from(ChatMessage message) {
		return new MessageResponse(
			message.getId(),
			message.getSenderType(),
			message.getMessageType(),
			message.getContent(),
			message.getPageNumber(),
			message.getStatus(),
			message.getCreatedAt()
		);
	}
}
