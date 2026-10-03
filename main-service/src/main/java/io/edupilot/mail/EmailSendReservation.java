package io.edupilot.mail;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "email_send_reservations", indexes = {
	@Index(name = "idx_email_reservations_time", columnList = "reserved_at"),
	@Index(name = "idx_email_reservations_delivery_time", columnList = "delivery_id, reserved_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_email_reservation_claim", columnNames = {"delivery_id", "claim_token"}))
public class EmailSendReservation {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "delivery_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private EmailDelivery delivery;
	@Column(name = "claim_token", nullable = false, length = 36, updatable = false)
	private String claimToken;
	@Column(name = "reserved_at", nullable = false, updatable = false)
	private Instant reservedAt;
	@Column(nullable = false, updatable = false)
	private int units;
	protected EmailSendReservation() { }
	public static EmailSendReservation reserved(EmailDelivery delivery, String token, Instant now) {
		EmailSendReservation reservation = new EmailSendReservation();
		reservation.delivery = delivery;
		reservation.claimToken = token;
		reservation.reservedAt = now;
		reservation.units = 1;
		return reservation;
	}
}
