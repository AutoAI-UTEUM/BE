package io.edupilot.mail;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
	 prefix = "edupilot.mail", name = "provider", havingValue = "logging", matchIfMissing = true
)
public class LoggingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);
	private final boolean production;

	public LoggingEmailSender(Environment environment) {
		this.production = environment.acceptsProfiles(Profiles.of("prod"));
		if (production && !environment.getProperty("edupilot.mail.allow-logging-in-prod", Boolean.class, false)) {
			throw new IllegalStateException("edupilot.mail.provider=logging is not allowed in prod; set provider=ses");
		}
		if (production) {
			log.warn("Logging mail provider selected in prod; message bodies are suppressed");
		}
	}

	@Override
	public EmailDeliveryResult send(EmailMessage message) {
		String providerMessageId = "logging-" + UUID.randomUUID();
		if (production) {
			log.atInfo()
				.addKeyValue("recipient", message.to())
				.addKeyValue("subject", message.subject())
				.log("Mail delivery simulated; body suppressed in prod");
		} else {
			log.atInfo()
				.addKeyValue("recipient", message.to())
				.addKeyValue("subject", message.subject())
				.addKeyValue("textBody", message.textBody())
				.addKeyValue("htmlBody", message.htmlBody())
				.log("Mail delivery simulated");
		}
		return new EmailDeliveryResult(providerMessageId);
	}
}
