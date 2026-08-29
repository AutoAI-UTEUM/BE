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

	@Enumerated(EnumType.STRING)
	@Column(name = "auth_provider", nullable = false, length = 20)
	private AuthProvider authProvider;

	@Column(name = "google_sub", unique = true, length = 64)
	private String googleSub;

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

	@Column(name = "new_material_notification", nullable = false)
	private boolean newMaterialNotification = true;

	@Column(name = "study_reminder", nullable = false)
	private boolean studyReminder = true;

	@Enumerated(EnumType.STRING)
	@Column(name = "ai_answer_style", nullable = false, length = 20)
	private AiAnswerStyle aiAnswerStyle = AiAnswerStyle.NORMAL;

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
		Instant consentedAt,
		AuthProvider authProvider,
		String googleSub
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
		this.authProvider = authProvider;
		this.googleSub = googleSub;
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
			consentedAt,
			AuthProvider.LOCAL,
			null
		);
	}

	public static User createGoogle(
		String email,
		String passwordHash,
		String name,
		UserRole role,
		String affiliation,
		boolean learningEmailOptIn,
		String termsVersion,
		String privacyVersion,
		Instant consentedAt,
		String googleSub
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
			consentedAt,
			AuthProvider.GOOGLE,
			googleSub
		);
	}

	public void linkGoogle(String googleSub) {
		this.googleSub = googleSub;
	}

	public void withdraw() {
		this.email = "deleted_" + id;
		this.name = "탈퇴 사용자";
		this.affiliation = null;
		this.avatarKey = null;
		this.learningEmailOptIn = false;
		this.termsVersion = null;
		this.privacyVersion = null;
		this.consentedAt = null;
		this.passwordHash = "!withdrawn:" + id;
		this.googleSub = null;
		this.status = UserStatus.DELETED;
	}

	public void updateProfile(String name, String affiliation) {
		if (name != null) {
			this.name = name;
		}
		this.affiliation = affiliation;
	}

	public void replaceAvatar(String avatarKey) {
		this.avatarKey = avatarKey;
	}

	public void updatePreferences(
		Boolean newMaterialNotification,
		Boolean studyReminder,
		AiAnswerStyle aiAnswerStyle
	) {
		if (newMaterialNotification != null) {
			this.newMaterialNotification = newMaterialNotification;
		}
		if (studyReminder != null) {
			this.studyReminder = studyReminder;
		}
		if (aiAnswerStyle != null) {
			this.aiAnswerStyle = aiAnswerStyle;
		}
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

	public AuthProvider getAuthProvider() {
		return authProvider;
	}

	public String getGoogleSub() {
		return googleSub;
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

	public boolean isNewMaterialNotification() {
		return newMaterialNotification;
	}

	public boolean isStudyReminder() {
		return studyReminder;
	}

	public AiAnswerStyle getAiAnswerStyle() {
		return aiAnswerStyle;
	}

	public UserRole getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}
}
