package io.edupilot.user;

import java.time.Instant;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;

/** Current business eligibility; input DOB and phone control never grant approval. */
public record UserBusinessAccessState(
	UserRole role,
	UserStatus status,
	EmailVerificationState emailVerification,
	Instant emailVerifiedAt,
	AccountAccessCohort accessCohort,
	AgeVerificationState ageVerification
) {
	public static UserBusinessAccessState from(User user) {
		return new UserBusinessAccessState(user.getRole(), user.getStatus(), user.getEmailVerificationState(),
			user.getEmailVerifiedAt(), user.getAccessCohort(), user.getAgeVerificationState());
	}

	public ErrorCode eligibilityFailure() {
		if (accessCohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		if (emailVerification != EmailVerificationState.VERIFIED || emailVerifiedAt == null) {
			return ErrorCode.EMAIL_VERIFICATION_REQUIRED;
		}
		return ageFailure(accessCohort, ageVerification);
	}

	public static ErrorCode ageFailure(AccountAccessCohort cohort, AgeVerificationState state) {
		if (cohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		// No approved age/guardian transition exists until its evidence and policy are decided.
		return state == AgeVerificationState.MANUAL_PENDING
			? ErrorCode.GUARDIAN_VERIFICATION_PENDING : ErrorCode.AGE_VERIFICATION_REQUIRED;
	}
}
