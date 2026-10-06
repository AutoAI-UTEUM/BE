package io.edupilot.mail;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_outbox", indexes = {
	@Index(name = "idx_email_outbox_due", columnList = "status, next_attempt_at, delivery_id"),
	@Index(name = "idx_email_outbox_lease", columnList = "status, lease_until"),
	@Index(name = "idx_email_outbox_expiry", columnList = "status, expires_at, delivery_id")
})
public class EmailOutbox {
	@Id @Column(name = "delivery_id")
	private Long id;
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", insertable = false, updatable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private EmailDelivery delivery;
	@Lob @Column(name = "encrypted_payload", columnDefinition = "LONGBLOB")
	private byte[] encryptedPayload;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
	private EmailOutboxStatus status;
	@Column(name = "lease_token", length = 36)
	private String leaseToken;
	@Column(name = "lease_until")
	private Instant leaseUntil;
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;
	@Column(name = "last_error_code", length = 80)
	private String lastErrorCode;

	protected EmailOutbox() { }

	public static EmailOutbox queued(EmailDelivery delivery, byte[] payload, Instant now, Instant expiry) {
		EmailOutbox job = new EmailOutbox();
		job.id = delivery.getId();
		job.delivery = delivery;
		job.encryptedPayload = payload;
		job.status = EmailOutboxStatus.READY;
		job.createdAt = now;
		job.nextAttemptAt = now;
		job.expiresAt = expiry;
		return job;
	}

	void claim(String token, Instant until) {
		status = EmailOutboxStatus.CLAIMED;
		leaseToken = token;
		leaseUntil = until;
	}
	void beginSending() { status = EmailOutboxStatus.SENDING; attemptCount++; }
	void retry(Instant next) {
		status = EmailOutboxStatus.RETRY;
		nextAttemptAt = next;
		leaseToken = null;
		leaseUntil = null;
		lastErrorCode = "THROTTLED_RETRY_PENDING";
	}
	void recoverClaim() {
		status = EmailOutboxStatus.READY;
		leaseToken = null;
		leaseUntil = null;
		lastErrorCode = "LEASE_RECOVERED_BEFORE_SEND";
	}
	void terminal(EmailOutboxStatus outcome, String code) {
		status = outcome;
		lastErrorCode = code;
		encryptedPayload = null;
		leaseUntil = null;
		// Preserve the fencing token so a still-running call can record a known late receipt.
	}
	/** 일반 만료와 달리 오래된 발송 결과가 연락처나 제공자 참조를 되살리지 못하게 합니다. */
	public void eraseGuardianPayload() {
		if (delivery.getType() != EmailDeliveryType.GUARDIAN_TEAM_NOTICE) {
			throw new IllegalStateException("보호자 팀 확인 메일 본문만 정리할 수 있습니다.");
		}
		if (encryptedPayload != null) {
			java.util.Arrays.fill(encryptedPayload, (byte) 0);
		}
		encryptedPayload = null;
		leaseToken = null;
		leaseUntil = null;
		lastErrorCode = "GUARDIAN_CONTACT_ERASED";
		if (status == EmailOutboxStatus.SENDING) {
			status = EmailOutboxStatus.UNKNOWN;
		} else if (status != EmailOutboxStatus.SENT && status != EmailOutboxStatus.UNKNOWN) {
			status = EmailOutboxStatus.FAILED;
		}
	}
	boolean ownedBy(String token) { return token != null && token.equals(leaseToken); }
	public Long getId() { return id; }
	public EmailOutboxStatus getStatus() { return status; }
	public int getAttemptCount() { return attemptCount; }
	public String getLastErrorCode() { return lastErrorCode; }
	EmailDelivery delivery() { return delivery; }
	byte[] payload() { return encryptedPayload; }
	Instant leaseUntil() { return leaseUntil; }
	Instant nextAttemptAt() { return nextAttemptAt; }
	Instant expiresAt() { return expiresAt; }
}
