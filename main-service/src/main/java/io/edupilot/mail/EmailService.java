package io.edupilot.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EmailService {
	private static final Logger log = LoggerFactory.getLogger(EmailService.class);
	private static final Pattern RECIPIENT = Pattern.compile(
		"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"
	);
	private final EmailDeliveryStore history;
	private final EmailOutboxStore outbox;
	private final EmailOutboxWorker worker;
	private final MailProperties properties;
	private final Clock clock;

	public EmailService(EmailDeliveryStore history, EmailOutboxStore outbox,
		EmailOutboxWorker worker, MailProperties properties, Clock clock) {
		this.history = history;
		this.outbox = outbox;
		this.worker = worker;
		this.properties = properties;
		this.clock = clock;
	}

	public Long sendAsync(EmailMessage message) {
		Duration lifetime = message != null && (message.type() == EmailDeliveryType.PASSWORD_RESET
			|| message.type() == EmailDeliveryType.EMAIL_VERIFY) ? Duration.ofMinutes(30) : Duration.ofHours(24);
		return sendAsync(message, clock.instant().plus(lifetime));
	}

	/** Provider failures are asynchronous. A failed durable payload insert rolls back its caller transaction. */
	public Long sendAsync(EmailMessage message, Instant expiresAt) {
		// 수신자 이력이 별도 트랜잭션에 남기 전에 지원하지 않는 보호자 발송을 차단합니다.
		if (message != null && message.type() == EmailDeliveryType.GUARDIAN_TEAM_NOTICE) {
			throw new IllegalArgumentException("보호자 팀 확인 메일은 일반 메일 경로에서 발송할 수 없습니다.");
		}
		if (message == null || message.to() == null || message.type() == null || message.textBody() == null
			|| expiresAt == null || !expiresAt.isAfter(clock.instant())) {
			log.warn("Mail request rejected: required field missing or expired");
			return null;
		}
		String recipient = message.to().trim();
		if (recipient.length() > 320 || !RECIPIENT.matcher(recipient).matches()) {
			log.warn("Mail request rejected: invalid recipient format");
			return null;
		}
		String subject = message.subject() == null ? "" : message.subject();
		EmailMessage normalized = new EmailMessage(recipient,
			subject.substring(0, Math.min(subject.length(), 255)), message.textBody(), message.htmlBody(), message.type());
		Long id;
		try {
			id = history.queue(normalized); // Metadata audit survives a caller rollback.
		} catch (RuntimeException error) {
			log.warn("Could not record mail request");
			return null;
		}
		if (!properties.enabled()) {
			markFailed(id, "DISABLED");
			return id;
		}
		boolean callerTransaction = TransactionSynchronizationManager.isActualTransactionActive()
			&& TransactionSynchronizationManager.isSynchronizationActive();
		try {
			outbox.enqueue(id, normalized, expiresAt);
		} catch (RuntimeException failure) {
			markFailed(id, "OUTBOX_QUEUE_FAILED");
			if (callerTransaction) {
				throw failure; // Never leave sendable mail for a rolled-back token/account change.
			}
			return id;
		}
		if (callerTransaction) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() { worker.kick(id); }
				@Override public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						markFailed(id, "CALLER_TRANSACTION_ROLLED_BACK");
					}
				}
			});
		} else {
			worker.kick(id);
		}
		return id;
	}

	private void markFailed(Long id, String code) {
		try { history.failed(id, code); }
		catch (RuntimeException failure) {
			log.atWarn().addKeyValue("deliveryId", id).log("Mail audit update unavailable");
		}
	}
}
