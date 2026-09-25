package io.edupilot.mail;

import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EmailService {

	private static final Logger log = LoggerFactory.getLogger(EmailService.class);
	private static final Pattern RECIPIENT = Pattern.compile(
		"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"
	);

	private final EmailDeliveryStore store;
	private final EmailSender sender;
	private final Executor executor;
	private final MailProperties properties;

	public EmailService(
		EmailDeliveryStore store,
		EmailSender sender,
		@Qualifier("mailExecutor") Executor executor,
		MailProperties properties
	) {
		this.store = store;
		this.sender = sender;
		this.executor = executor;
		this.properties = properties;
	}

	/** Returns null only when the request cannot be recorded; callers never receive mail failures. */
	public Long sendAsync(EmailMessage message) {
		if (message == null || message.to() == null || message.type() == null
			|| message.textBody() == null) {
			log.warn("Mail request rejected: required field missing");
			return null;
		}
		String recipient = message.to().trim();
		if (recipient.length() > 320 || !RECIPIENT.matcher(recipient).matches()) {
			log.warn("Mail request rejected: invalid recipient format");
			return null;
		}
		String subject = message.subject() == null ? "" : message.subject();
		EmailMessage normalized = new EmailMessage(
			recipient,
			subject.substring(0, Math.min(subject.length(), 255)),
			message.textBody(),
			message.htmlBody(),
			message.type()
		);
		Long deliveryId;
		try {
			// The record survives a caller transaction rollback (for audit/fail-soft).
			deliveryId = store.queue(normalized);
		} catch (RuntimeException exception) {
			log.warn("Could not queue mail delivery");
			return null;
		}
		if (!properties.enabled()) {
			markFailed(deliveryId, "DISABLED");
			return deliveryId;
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()
			&& TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						dispatch(deliveryId, normalized);
					}

					@Override
					public void afterCompletion(int status) {
						if (status != STATUS_COMMITTED) {
							markFailed(deliveryId, "CALLER_TRANSACTION_ROLLED_BACK");
						}
					}
				}
			);
			return deliveryId;
		}
		dispatch(deliveryId, normalized);
		return deliveryId;
	}

	private void dispatch(Long deliveryId, EmailMessage message) {
		try {
			executor.execute(() -> deliver(deliveryId, message));
		} catch (RuntimeException exception) {
			markFailed(deliveryId, "EXECUTOR_REJECTED");
			log.atWarn()
				.addKeyValue("deliveryId", deliveryId)
				.addKeyValue("errorType", exception.getClass().getSimpleName())
				.log("Mail executor rejected delivery");
		}
	}

	private void deliver(Long deliveryId, EmailMessage message) {
		try {
			if (!store.reserve(deliveryId)) {
				return;
			}
			for (int attempt = 1; attempt <= 2; attempt++) {
				store.attempt(deliveryId);
				EmailDeliveryResult result;
				try {
					result = sender.send(message);
				} catch (RuntimeException exception) {
					if (attempt == 2) {
						markFailed(deliveryId, summarize(exception, message));
						log.atWarn()
							.addKeyValue("deliveryId", deliveryId)
							.addKeyValue("errorType", exception.getClass().getSimpleName())
							.log("Mail delivery failed after retry");
					}
					continue;
				}
				// A history write failure must never trigger a duplicate SES send.
				store.sent(deliveryId, result.providerMessageId());
				return;
			}
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("deliveryId", deliveryId)
				.addKeyValue("errorType", exception.getClass().getSimpleName())
				.log("Mail delivery history unavailable");
		}
	}

	private void markFailed(Long deliveryId, String summary) {
		try {
			store.failed(deliveryId, summary);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("deliveryId", deliveryId)
				.log("Could not mark mail delivery failed");
		}
	}

	private String summarize(RuntimeException exception, EmailMessage message) {
		String raw = exception.getMessage() == null ? "" : exception.getMessage();
		if (!message.textBody().isEmpty()) {
			raw = raw.replace(message.textBody(), "[REDACTED]");
		}
		if (message.htmlBody() != null && !message.htmlBody().isEmpty()) {
			raw = raw.replace(message.htmlBody(), "[REDACTED]");
		}
		raw = raw.replaceAll("(?i)\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", "[REDACTED]")
			.replaceAll("(?i)(?:https?://|www\\.)\\S+", "[REDACTED]")
			.replaceAll("(?i)(?:token|secret|password|credential|authorization)\\s*[:=]\\s*\\S+", "[REDACTED]");
		String summary = exception.getClass().getSimpleName() + ": " + raw;
		return summary.substring(0, Math.min(summary.length(), 200));
	}
}
