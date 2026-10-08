package io.edupilot.mail;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmailOutboxWorker {
	private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
	private final EmailOutboxStore outbox;
	private final EmailDeliveryStore history;
	private final EmailSender sender;
	private final MailProperties properties;
	private final Executor executor;
	private final Set<Long> queuedIds = ConcurrentHashMap.newKeySet();

	public EmailOutboxWorker(EmailOutboxStore outbox, EmailDeliveryStore history, EmailSender sender,
		MailProperties properties, @Qualifier("mailExecutor") Executor executor) {
		this.outbox = outbox;
		this.history = history;
		this.sender = sender;
		this.properties = properties;
		this.executor = executor;
	}

	@Scheduled(fixedDelayString = "${edupilot.mail.outbox.recovery-delay-ms:30000}",
		initialDelayString = "${edupilot.mail.outbox.recovery-delay-ms:30000}")
	public void recoverPending() {
		try {
			// Expired payloads and leases must make progress even when sending is disabled.
			for (Long id : outbox.cleanupIds()) {
				try {
					outbox.recover(id);
				} catch (RuntimeException error) {
					log.atWarn().addKeyValue("deliveryId", id)
						.addKeyValue("errorType", error.getClass().getSimpleName())
						.log("Mail outbox row recovery unavailable; durable work remains pending");
				}
			}
			if (properties.enabled()) {
				for (Long id : outbox.dispatchableIds()) {
					kick(id);
				}
			}
		} catch (RuntimeException error) {
			log.atWarn().addKeyValue("errorType", error.getClass().getSimpleName())
				.log("Mail outbox recovery unavailable");
		}
	}

	public void kick(Long id) {
		if (!properties.enabled() || outbox.dispatchBlocked(id) || !queuedIds.add(id)) {
			return;
		}
		try {
			executor.execute(() -> {
				try { deliver(id); } finally { queuedIds.remove(id); }
			});
		} catch (RuntimeException error) {
			queuedIds.remove(id);
			try { outbox.executorRejected(id); } catch (RuntimeException ignored) { }
			log.atWarn().addKeyValue("deliveryId", id).log("Mail executor rejected; durable retry remains pending");
		}
	}

	private void deliver(Long id) {
		try {
			var claim = outbox.claim(id);
			if (claim == null) {
				return;
			}
			if (!history.reserve(id, claim.token())) {
				outbox.rateLimited(claim);
				return;
			}
			if (!outbox.beginSending(claim)) {
				return;
			}
			EmailDeliveryResult result;
			try {
				result = sender.send(claim.message());
			} catch (EmailSendRejection rejection) {
				outbox.rejected(claim, rejection.retryable());
				return;
			} catch (RuntimeException uncertain) {
				outbox.unknown(claim);
				log.atWarn().addKeyValue("deliveryId", id)
					.addKeyValue("errorType", uncertain.getClass().getSimpleName())
					.log("Mail provider outcome unknown; automatic resend blocked");
				return;
			}
			// A failed receipt transaction remains SENDING, then recovers to UNKNOWN without another send.
			outbox.sent(claim, result.providerMessageId());
		} catch (RuntimeException error) {
			log.atWarn().addKeyValue("deliveryId", id).addKeyValue("errorType", error.getClass().getSimpleName())
				.log("Mail outbox delivery history unavailable");
		}
	}
}
