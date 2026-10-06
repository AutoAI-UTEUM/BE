package io.edupilot.user;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
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
@DynamicUpdate
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "email_verification_state", nullable = false, length = 20)
	private EmailVerificationState emailVerificationState = EmailVerificationState.UNKNOWN;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

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

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_reason", length = 500)
	private String suspendedReason;

	@Column(name = "suspended_by")
	private Long suspendedBy;

	@Column(name = "last_active_at")
	private Instant lastActiveAt;

	@Column(name = "date_of_birth")
	private java.time.LocalDate dateOfBirth;

	@Enumerated(EnumType.STRING)
	@Column(name = "age_verification_state",nullable = false,length = 30,columnDefinition = "varchar(30) default 'UNKNOWN'")
	private io.edupilot.guardian.AgeVerificationState ageVerificationState = io.edupilot.guardian.AgeVerificationState.UNKNOWN;

	@Column(name = "guardian_approved_until")
	private Instant guardianApprovedUntil;

	@Column(name = "guardian_ai_consent_allowed", nullable = false, columnDefinition = "boolean default false")
	private boolean guardianAiConsentAllowed;

	@Column(name = "guardian_consent_epoch", nullable = false, columnDefinition = "bigint default 0")
	private long guardianConsentEpoch;

	@Column(name = "guardian_approval_policy_digest", length = 64)
	private String guardianApprovalPolicyDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "access_cohort", nullable = false, length = 24, columnDefinition = "varchar(24) default 'NEW_SIGNUP'")
	private AccountAccessCohort accessCohort = AccountAccessCohort.NEW_SIGNUP;

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

	public void changePassword(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	/** Creation-only capture. The signup service validates the approved KST calendar policy. */
	public void recordSignupDateOfBirth(java.time.LocalDate date) {
		if(id!=null || date==null) { throw new IllegalStateException("Birth date can only be captured during signup"); }
		// Storage bounds also protect internal fixture/import callers. No guardian evidence is inferred.
		if(date.getYear()<1 || date.getYear()>9999) {
			throw new io.edupilot.global.error.BusinessException(io.edupilot.global.error.ErrorCode.VALIDATION_FAILED);
		}
		this.dateOfBirth=date;
	}
	public void beginGuardianVerification() {
		if(!isActive()) { throw new IllegalStateException("Inactive account cannot request verification"); }
		if (ageVerificationState == io.edupilot.guardian.AgeVerificationState.TEAM_APPROVED || guardianApprovedUntil != null) {
			clearGuardianTeamApproval(true);
		} else {
			ageVerificationState = io.edupilot.guardian.AgeVerificationState.MANUAL_PENDING;
			guardianAiConsentAllowed = false;
		}
	}
	/** Only the transactional team decision service supplies an explicitly reviewed expiry and scope. */
	public void recordGuardianTeamApproval(Instant until, boolean aiAllowed) {
		if (!isActive() || isLegacyAccessExempt() || dateOfBirth == null) {
			throw new IllegalStateException("현재 계정에는 보호자 검토 승인을 적용할 수 없습니다.");
		}
		guardianApprovedUntil = java.util.Objects.requireNonNull(until);
		guardianApprovalPolicyDigest = null;
		guardianAiConsentAllowed = aiAllowed;
		ageVerificationState = io.edupilot.guardian.AgeVerificationState.TEAM_APPROVED;
		guardianConsentEpoch = Math.incrementExact(guardianConsentEpoch);
	}
	public void clearGuardianTeamApproval(boolean pending) {
		guardianApprovedUntil = null;
		guardianApprovalPolicyDigest = null;
		guardianAiConsentAllowed = false;
		ageVerificationState = pending ? io.edupilot.guardian.AgeVerificationState.MANUAL_PENDING
			: io.edupilot.guardian.AgeVerificationState.UNKNOWN;
		guardianConsentEpoch = Math.incrementExact(guardianConsentEpoch);
	}
	public Instant getGuardianApprovedUntil() { return guardianApprovedUntil; }
	public boolean isGuardianAiConsentAllowed() { return guardianAiConsentAllowed; }
	public long getGuardianConsentEpoch() { return guardianConsentEpoch; }
	public String getGuardianApprovalPolicyDigest() { return guardianApprovalPolicyDigest; }
	public void recordGuardianTeamPolicyDigest(String digest) {
		if (ageVerificationState != io.edupilot.guardian.AgeVerificationState.TEAM_APPROVED
			|| digest == null || !digest.matches("[0-9a-f]{64}")) {
			throw new IllegalStateException("Reviewed guardian policy evidence is required");
		}
		guardianApprovalPolicyDigest = digest;
	}
	public java.time.LocalDate getDateOfBirth() { return dateOfBirth; }
	public io.edupilot.guardian.AgeVerificationState getAgeVerificationState() { return ageVerificationState; }
	public AccountAccessCohort getAccessCohort() { return accessCohort; }
	public boolean isLegacyAccessExempt() { return accessCohort == AccountAccessCohort.LEGACY_EXEMPT; }
	public boolean isEmailVerificationRequired() { return !isEmailVerified() && !isLegacyAccessExempt(); }

	public void beginEmailVerification() {
		if (!isEmailVerified()) {
			emailVerificationState = EmailVerificationState.PENDING;
			emailVerifiedAt = null;
		}
	}

	public void verifyEmail(Instant verifiedAt) {
		emailVerifiedAt = java.util.Objects.requireNonNull(verifiedAt);
		emailVerificationState = EmailVerificationState.VERIFIED;
	}

	public boolean isEmailVerified() {
		return emailVerificationState == EmailVerificationState.VERIFIED && emailVerifiedAt != null;
	}

	public EmailVerificationState getEmailVerificationState() { return emailVerificationState; }
	public Instant getEmailVerifiedAt() { return emailVerifiedAt; }

	public void withdraw() {
		clearGuardianTeamApproval(false);
		this.dateOfBirth=null;
		this.ageVerificationState=io.edupilot.guardian.AgeVerificationState.UNKNOWN;
		this.email = "deleted_" + id;
		this.emailVerificationState = EmailVerificationState.UNKNOWN;
		this.emailVerifiedAt = null;
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
		this.suspendedAt = null;
		this.suspendedReason = null;
		this.suspendedBy = null;
	}

	public void suspend(String reason, Long actorUserId, Instant now) {
		this.status = UserStatus.SUSPENDED;
		this.suspendedReason = reason;
		this.suspendedBy = actorUserId;
		this.suspendedAt = now;
	}

	public void reinstate() {
		this.status = UserStatus.ACTIVE;
		this.suspendedAt = null;
		this.suspendedReason = null;
		this.suspendedBy = null;
	}

	public void changeRole(UserRole role) {
		this.role = role;
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

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public String getSuspendedReason() {
		return suspendedReason;
	}

	public Long getSuspendedBy() {
		return suspendedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastActiveAt() {
		return lastActiveAt;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}
}
