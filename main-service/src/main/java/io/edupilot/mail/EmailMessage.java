package io.edupilot.mail;

public record EmailMessage(
	String to,
	String subject,
	String textBody,
	String htmlBody,
	EmailDeliveryType type
) {
	@Override
	public String toString() {
		return "EmailMessage[type=" + type + "]";
	}
}
