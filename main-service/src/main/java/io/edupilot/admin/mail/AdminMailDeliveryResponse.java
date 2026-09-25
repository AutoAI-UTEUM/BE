package io.edupilot.admin.mail;

import java.time.Instant;

import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryType;

public record AdminMailDeliveryResponse(
	Long id,
	String recipient,
	EmailDeliveryType type,
	EmailDeliveryStatus status,
	String subject,
	String providerMessageId,
	String errorSummary,
	int attemptCount,
	Instant createdAt,
	Instant sentAt
) {
	public static AdminMailDeliveryResponse from(EmailDelivery delivery) {
		return new AdminMailDeliveryResponse(
			delivery.getId(),
			delivery.getRecipient(),
			delivery.getType(),
			delivery.getStatus(),
			delivery.getSubject(),
			delivery.getProviderMessageId(),
			delivery.getErrorSummary(),
			delivery.getAttemptCount(),
			delivery.getCreatedAt(),
			delivery.getSentAt()
		);
	}
}
