package io.edupilot.mail;

public record EmailDeliveryResult(String providerMessageId) {
	public EmailDeliveryResult {
		if (providerMessageId == null || providerMessageId.isBlank()) {
			throw new IllegalStateException("Mail provider receipt is missing");
		}
	}
}
