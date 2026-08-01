package io.edupilot.user;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 255)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 100)
	private String affiliation;

	@Column(name = "avatar_key", length = 255)
	private String avatarKey;

	@Column(name = "learning_email_opt_in", nullable = false)
	private boolean learningEmailOptIn;

	@Column(name = "terms_version", length = 50)
	private String termsVersion;

	@Column(name = "privacy_version", length = 50)
	private String privacyVersion;

	@Column(name = "consented_at")
	private Instant consentedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
	}

	private User(
		String email,
		String passwordHash,
		String name,
		UserRole role,
		String affiliation,
		boolean learningEmailOptIn,
		String termsVersion,
		String privacyVersion,
		Instant consentedAt
	) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role;
		this.affiliation = affiliation;
		this.learningEmailOptIn = learningEmailOptIn;
		this.termsVersion = termsVersion;
		this.privacyVersion = privacyVersion;
		this.consentedAt = consentedAt;
		this.status = UserStatus.ACTIVE;
	}

	public static User create(String email, String passwordHash, String name) {
		return create(email, passwordHash, name, UserRole.LEARNER);
	}

	public static User create(
		String email,
		String passwordHash,
		String name,
		UserRole role
	) {
		return create(email, passwordHash, name, role, null, false, null, null, null);
	}

	public static User create(
		String email,
		String passwordHash,
		String name,
		UserRole role,
		String affiliation,
		boolean learningEmailOptIn,
		String termsVersion,
		String privacyVersion,
		Instant consentedAt
	) {
		return new User(
			email,
			passwordHash,
			name,
			role,
			affiliation,
			learningEmailOptIn,
			termsVersion,
			privacyVersion,
			consentedAt
		);
	}

	public void withdraw() {
		this.email = "deleted_" + id;
		this.name = "탈퇴 사용자";
		this.passwordHash = "!withdrawn:" + id;
		this.status = UserStatus.DELETED;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getName() {
		return name;
	}

	public String getAffiliation() {
		return affiliation;
	}

	public String getAvatarKey() {
		return avatarKey;
	}

	public String getAvatarUrl() {
		return avatarKey == null ? null : "/api/users/me/avatar";
	}

	public boolean isLearningEmailOptIn() {
		return learningEmailOptIn;
	}

	public String getTermsVersion() {
		return termsVersion;
	}

	public String getPrivacyVersion() {
		return privacyVersion;
	}

	public Instant getConsentedAt() {
		return consentedAt;
	}

	public UserRole getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}
}
