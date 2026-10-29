package io.edupilot.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

@Component
@ConditionalOnProperty(prefix = "edupilot.mail", name = "provider", havingValue = "ses")
public class SesEmailSender implements EmailSender {

	private final SesV2Client client;
	private final MailProperties properties;

	public SesEmailSender(SesV2Client client, MailProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	@Override
	public EmailDeliveryResult send(EmailMessage message) {
		Body.Builder body = Body.builder()
			.text(Content.builder().data(message.textBody()).charset("UTF-8").build());
		if (StringUtils.hasText(message.htmlBody())) {
			body.html(Content.builder().data(message.htmlBody()).charset("UTF-8").build());
		}
		SendEmailRequest.Builder request = SendEmailRequest.builder()
			.fromEmailAddress(properties.from())
			.destination(Destination.builder().toAddresses(message.to()).build())
			.content(EmailContent.builder().simple(Message.builder()
				.subject(Content.builder().data(message.subject()).charset("UTF-8").build())
				.body(body.build())
				.build()).build());
		if (StringUtils.hasText(properties.replyTo())) {
			request.replyToAddresses(properties.replyTo());
		}
		try {
			return new EmailDeliveryResult(client.sendEmail(request.build()).messageId());
		} catch (SesV2Exception rejected) {
			if (rejected.statusCode() == 429) {
				throw new EmailSendRejection(true);
			}
			if (rejected.statusCode() >= 400 && rejected.statusCode() < 500) {
				throw new EmailSendRejection(false);
			}
			throw rejected; // Unknown server/transport outcomes are never blindly resent.
		}
	}
}
