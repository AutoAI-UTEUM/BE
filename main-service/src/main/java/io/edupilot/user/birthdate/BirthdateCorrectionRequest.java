package io.edupilot.user.birthdate;

import java.time.Instant;
import java.time.LocalDate;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Administrator intake only. There is no DOB mutation or approval transition. */
@Entity
@Table(name = "birthdate_correction_requests")
public class BirthdateCorrectionRequest {
	public enum State { PENDING, WITHDRAWN }

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Column(name = "requested_date_of_birth")
	private LocalDate requestedDateOfBirth;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private State state;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	protected BirthdateCorrectionRequest() { }

	public static BirthdateCorrectionRequest pending(User user, LocalDate date, Instant now) {
		var request = new BirthdateCorrectionRequest();
		request.user = user;
		request.requestedDateOfBirth = date;
		request.state = State.PENDING;
		request.requestedAt = now;
		return request;
	}

	public Long getId() { return id; }
	public Long getUserId() { return user.getId(); }
	public LocalDate getRequestedDateOfBirth() { return requestedDateOfBirth; }
	public State getState() { return state; }
	public Instant getRequestedAt() { return requestedAt; }

	@Override public String toString() { return "BirthdateCorrectionRequest[REDACTED]"; }
}
