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

	public void queuedReason(String code) {
		if (status == EmailDeliveryStatus.QUEUED) {
			errorSummary = code;
		}
	}

	public void sent(String messageId, Instant now) {
		if (erasedGuardianContact()) {
			return;
		}
		status = EmailDeliveryStatus.SENT;
		providerMessageId = messageId;
		sentAt = now;
		errorSummary = null;
	}

	public void failed(String summary) {
		if (erasedGuardianContact()) {
			return;
		}
		status = EmailDeliveryStatus.FAILED;
		errorSummary = summary;
	}

	public void rateLimited() {
		status = EmailDeliveryStatus.RATE_LIMITED;
	}

	/** 보호자 요청에 연결된 개인정보만 지웁니다. 전역 발송량 예약은 그대로 유지합니다. */
	public void eraseGuardianContact() {
		if (type != EmailDeliveryType.GUARDIAN_TEAM_NOTICE) {
			throw new IllegalStateException("보호자 팀 확인 메일만 정리할 수 있습니다.");
		}
		recipient = "";
		subject = "";
		providerMessageId = null;
		errorSummary = "GUARDIAN_CONTACT_ERASED";
		sentAt = null;
		if (status != EmailDeliveryStatus.SENT) {
			status = EmailDeliveryStatus.FAILED;
		}
	}

	private boolean erasedGuardianContact() {
		return type == EmailDeliveryType.GUARDIAN_TEAM_NOTICE && recipient.isEmpty();
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
