package io.edupilot.auth;

import java.time.Duration;
import java.time.Instant;

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
@Table(name = "auth_sessions")
public class AuthSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "last_activity_at", nullable = false)
	private Instant lastActivityAt;

	@Column(name = "idle_expires_at", nullable = false)
	private Instant idleExpiresAt;

	@Column(name = "absolute_expires_at", nullable = false)
	private Instant absoluteExpiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AuthSession() {
	}

	private AuthSession(
		User user,
		Instant lastActivityAt,
		Instant idleExpiresAt,
		Instant absoluteExpiresAt
	) {
		this.user = user;
		this.lastActivityAt = lastActivityAt;
		this.idleExpiresAt = idleExpiresAt;
		this.absoluteExpiresAt = absoluteExpiresAt;
	}

	public static AuthSession create(
		User user,
		Instant now,
		Duration idleTtl,
		Duration absoluteTtl
	) {
		Instant absoluteExpiresAt = now.plus(absoluteTtl);
		return new AuthSession(
			user,
			now,
			boundedIdleExpiresAt(now, idleTtl, absoluteExpiresAt),
			absoluteExpiresAt
		);
	}

	public static AuthSession adoptLegacy(
		User user,
		Instant now,
		Duration idleTtl,
		Instant absoluteExpiresAt
	) {
		return new AuthSession(
			user,
			now,
			boundedIdleExpiresAt(now, idleTtl, absoluteExpiresAt),
			absoluteExpiresAt
		);
	}

	public void touch(Instant now, Duration idleTtl) {
		this.lastActivityAt = now;
		this.idleExpiresAt = boundedIdleExpiresAt(now, idleTtl, absoluteExpiresAt);
	}

	public void revoke(Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
		}
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isIdleExpired(Instant now) {
		return !idleExpiresAt.isAfter(now);
	}

	public boolean isAbsoluteExpired(Instant now) {
		return !absoluteExpiresAt.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public Instant getLastActivityAt() {
		return lastActivityAt;
	}

	public Instant getIdleExpiresAt() {
		return idleExpiresAt;
	}

	public Instant getAbsoluteExpiresAt() {
		return absoluteExpiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	private static Instant boundedIdleExpiresAt(
		Instant now,
		Duration idleTtl,
		Instant absoluteExpiresAt
	) {
		Instant idleExpiresAt = now.plus(idleTtl);
		return idleExpiresAt.isBefore(absoluteExpiresAt)
			? idleExpiresAt
			: absoluteExpiresAt;
	}
}
