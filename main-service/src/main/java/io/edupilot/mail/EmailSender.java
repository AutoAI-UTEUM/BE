package io.edupilot.mail;

public interface EmailSender {
	EmailDeliveryResult send(EmailMessage message);
}
