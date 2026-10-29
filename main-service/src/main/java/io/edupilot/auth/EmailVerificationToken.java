package io.edupilot.auth;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
import jakarta.persistence.Index;

@Entity
@Table(name = "email_verification_tokens", indexes = {
	@Index(name = "idx_email_verification_user_created", columnList = "user_id, created_at"),
	@Index(name = "idx_email_verification_expiry", columnList = "expires_at")
})
public class EmailVerificationToken {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private User user;
	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;
	@Column(name = "email_hash", nullable = false, length = 64)
	private String emailHash;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
	@Column(name = "used_at")
	private Instant usedAt;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
	protected EmailVerificationToken() { }
	public static EmailVerificationToken create(User user, String hash, String emailHash, Instant now, Instant expiry) {
		EmailVerificationToken token = new EmailVerificationToken();
		token.user = user; token.tokenHash = hash; token.emailHash = emailHash;
		token.createdAt = now; token.expiresAt = expiry;
		return token;
	}
	public boolean isUsable(Instant now, String currentEmailHash) {
		return usedAt == null && expiresAt.isAfter(now) && emailHash.equals(currentEmailHash);
	}
	public void use(Instant now) { usedAt = now; }
	public Long getId() { return id; }
	public String getTokenHash() { return tokenHash; }
	public Instant getExpiresAt() { return expiresAt; }
	public Instant getUsedAt() { return usedAt; }
}
