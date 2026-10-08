package io.edupilot.user;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.guardian.BirthdatePolicy;

/** Current business eligibility; the DOB year rule is distinct from verified guardian evidence. */
public record UserBusinessAccessState(
	UserRole role,
	UserStatus status,
	EmailVerificationState emailVerification,
	Instant emailVerifiedAt,
	AccountAccessCohort accessCohort,
	AgeVerificationState ageVerification,
	LocalDate dateOfBirth,
	Instant guardianApprovedUntil,
	boolean guardianAiConsentAllowed,
	long guardianConsentEpoch,
	String guardianApprovalPolicyDigest
) {
	public UserBusinessAccessState(UserRole role, UserStatus status, EmailVerificationState emailVerification,
		Instant emailVerifiedAt, AccountAccessCohort accessCohort, AgeVerificationState ageVerification, LocalDate dateOfBirth) {
		this(role, status, emailVerification, emailVerifiedAt, accessCohort, ageVerification, dateOfBirth, null, false, 0, null);
	}
	public UserBusinessAccessState(UserRole role, UserStatus status, EmailVerificationState emailVerification,
		Instant emailVerifiedAt, AccountAccessCohort accessCohort, AgeVerificationState ageVerification, LocalDate dateOfBirth,
		Instant guardianApprovedUntil, boolean guardianAiConsentAllowed, long guardianConsentEpoch) {
		this(role, status, emailVerification, emailVerifiedAt, accessCohort, ageVerification, dateOfBirth,
			guardianApprovedUntil, guardianAiConsentAllowed, guardianConsentEpoch, null);
	}
	public static UserBusinessAccessState from(User user) {
		return new UserBusinessAccessState(user.getRole(), user.getStatus(), user.getEmailVerificationState(),
			user.getEmailVerifiedAt(), user.getAccessCohort(), user.getAgeVerificationState(), user.getDateOfBirth(),
			user.getGuardianApprovedUntil(), user.isGuardianAiConsentAllowed(), user.getGuardianConsentEpoch(),
			user.getGuardianApprovalPolicyDigest());
	}

	public ErrorCode eligibilityFailure(Clock clock) {
		return eligibilityFailure(clock, false);
	}

	public ErrorCode eligibilityFailure(Clock clock, boolean teamReviewEnabled) {
		return eligibilityFailure(clock, teamReviewEnabled, null);
	}

	public ErrorCode eligibilityFailure(Clock clock, boolean teamReviewEnabled, String expectedPolicyDigest) {
		if (accessCohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		if (emailVerification != EmailVerificationState.VERIFIED || emailVerifiedAt == null) {
			return ErrorCode.EMAIL_VERIFICATION_REQUIRED;
		}
		return ageFailure(accessCohort, ageVerification, dateOfBirth, guardianApprovedUntil,
			guardianApprovalPolicyDigest, expectedPolicyDigest, clock, teamReviewEnabled);
	}

	public ErrorCode aiEligibilityFailure(Clock clock) { return aiEligibilityFailure(clock, false); }

	public ErrorCode aiEligibilityFailure(Clock clock, boolean teamReviewEnabled) {
		return aiEligibilityFailure(clock, teamReviewEnabled, null);
	}

	public ErrorCode aiEligibilityFailure(Clock clock, boolean teamReviewEnabled, String expectedPolicyDigest) {
		ErrorCode failure = eligibilityFailure(clock, teamReviewEnabled, expectedPolicyDigest);
		if (failure != null) return failure;
		if (accessCohort != AccountAccessCohort.LEGACY_EXEMPT && !BirthdatePolicy.guardianNotRequired(dateOfBirth, clock)
			&& !guardianAiConsentAllowed) return ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED;
		return null;
	}

	public static ErrorCode ageFailure(AccountAccessCohort cohort, AgeVerificationState state, LocalDate dateOfBirth, Clock clock) {
		return ageFailure(cohort, state, dateOfBirth, null, clock, false);
	}

	public static ErrorCode ageFailure(AccountAccessCohort cohort, AgeVerificationState state, LocalDate dateOfBirth,
		Instant approvedUntil, Clock clock, boolean teamReviewEnabled) {
		return ageFailure(cohort, state, dateOfBirth, approvedUntil, null, null, clock, teamReviewEnabled);
	}

	public static ErrorCode ageFailure(AccountAccessCohort cohort, AgeVerificationState state, LocalDate dateOfBirth,
		Instant approvedUntil, String approvedPolicyDigest, String expectedPolicyDigest, Clock clock, boolean teamReviewEnabled) {
		if (cohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		if (BirthdatePolicy.guardianNotRequired(dateOfBirth, clock)) return null;
		boolean validDate = dateOfBirth != null && dateOfBirth.getYear() >= 1 && dateOfBirth.getYear() <= 9999
			&& !dateOfBirth.isAfter(BirthdatePolicy.today(clock));
		if (teamReviewEnabled && validDate && state == AgeVerificationState.TEAM_APPROVED
			&& approvedUntil != null && approvedUntil.isAfter(clock.instant())
			&& expectedPolicyDigest != null && expectedPolicyDigest.equals(approvedPolicyDigest)) return null;
		// Year difference <=14, missing or invalid DOB cannot infer guardian approval.
		return state == AgeVerificationState.MANUAL_PENDING
			? ErrorCode.GUARDIAN_VERIFICATION_PENDING : ErrorCode.AGE_VERIFICATION_REQUIRED;
	}

	@Override public String toString() { return "UserBusinessAccessState[REDACTED]"; }
}
