package io.edupilot.schedule;

import java.time.Instant;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_schedules")
@Check(constraints = "ends_at >= starts_at")
public class UserSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "has_time", nullable = false)
	private boolean hasTime;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserSchedule() {
	}

	private UserSchedule(
		User user,
		String title,
		Instant startsAt,
		Instant endsAt,
		boolean hasTime
	) {
		this.user = user;
		this.title = title;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.hasTime = hasTime;
	}

	public static UserSchedule create(
		User user,
		String title,
		Instant startsAt,
		Instant endsAt,
		boolean hasTime
	) {
		return new UserSchedule(user, title, startsAt, endsAt, hasTime);
	}

	public void update(
		String title,
		Instant startsAt,
		Instant endsAt,
		Boolean hasTime
	) {
		if (title != null) {
			this.title = title;
		}
		if (startsAt != null) {
			this.startsAt = startsAt;
		}
		if (endsAt != null) {
			this.endsAt = endsAt;
		}
		if (hasTime != null) {
			this.hasTime = hasTime;
		}
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public boolean hasTime() {
		return hasTime;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
