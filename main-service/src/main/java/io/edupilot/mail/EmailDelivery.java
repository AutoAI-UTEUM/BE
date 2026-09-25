package io.edupilot.mail;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_deliveries", indexes = {
	@Index(name = "idx_email_deliveries_recipient_created", columnList = "recipient, created_at"),
	@Index(name = "idx_email_deliveries_status_created", columnList = "status, created_at")
})
public class EmailDelivery {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 320)
	private String recipient;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private EmailDeliveryType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EmailDeliveryStatus status;

	@Column(nullable = false, length = 255)
	private String subject;

	@Column(name = "provider_message_id", length = 255)
	private String providerMessageId;

	@Column(name = "error_summary", length = 500)
	private String errorSummary;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	protected EmailDelivery() {
	}

	private EmailDelivery(EmailMessage message, Instant now) {
		this.recipient = message.to();
		this.type = message.type();
		this.status = EmailDeliveryStatus.QUEUED;
		this.subject = message.subject();
		this.createdAt = now;
	}

	public static EmailDelivery queued(EmailMessage message, Instant now) {
		return new EmailDelivery(message, now);
	}

	public void attempt() {
		attemptCount++;
	}

	public void sent(String messageId, Instant now) {
		status = EmailDeliveryStatus.SENT;
		providerMessageId = messageId;
		sentAt = now;
		errorSummary = null;
	}

	public void failed(String summary) {
		status = EmailDeliveryStatus.FAILED;
		errorSummary = summary;
	}

	public void rateLimited() {
		status = EmailDeliveryStatus.RATE_LIMITED;
	}

	public Long getId() {
		return id;
	}

	public String getRecipient() {
		return recipient;
	}

	public EmailDeliveryType getType() {
		return type;
	}

	public EmailDeliveryStatus getStatus() {
		return status;
	}

	public String getSubject() {
		return subject;
	}

	public String getProviderMessageId() {
		return providerMessageId;
	}

	public String getErrorSummary() {
		return errorSummary;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}
}
