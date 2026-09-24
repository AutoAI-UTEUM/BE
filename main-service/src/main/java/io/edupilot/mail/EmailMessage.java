package io.edupilot.mail;

public record EmailMessage(
	String to,
	String subject,
	String textBody,
	String htmlBody,
	EmailDeliveryType type
) {
}
