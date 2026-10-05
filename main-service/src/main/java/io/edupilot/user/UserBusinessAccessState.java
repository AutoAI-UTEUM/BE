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
	LocalDate dateOfBirth
) {
	public static UserBusinessAccessState from(User user) {
		return new UserBusinessAccessState(user.getRole(), user.getStatus(), user.getEmailVerificationState(),
			user.getEmailVerifiedAt(), user.getAccessCohort(), user.getAgeVerificationState(), user.getDateOfBirth());
	}

	public ErrorCode eligibilityFailure(Clock clock) {
		if (accessCohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		if (emailVerification != EmailVerificationState.VERIFIED || emailVerifiedAt == null) {
			return ErrorCode.EMAIL_VERIFICATION_REQUIRED;
		}
		return ageFailure(accessCohort, ageVerification, dateOfBirth, clock);
	}

	public static ErrorCode ageFailure(AccountAccessCohort cohort, AgeVerificationState state, LocalDate dateOfBirth, Clock clock) {
		if (cohort == AccountAccessCohort.LEGACY_EXEMPT) return null;
		if (BirthdatePolicy.guardianNotRequired(dateOfBirth, clock)) return null;
		// Year difference <=14, missing or invalid DOB cannot infer guardian approval.
		return state == AgeVerificationState.MANUAL_PENDING
			? ErrorCode.GUARDIAN_VERIFICATION_PENDING : ErrorCode.AGE_VERIFICATION_REQUIRED;
	}

	@Override public String toString() { return "UserBusinessAccessState[REDACTED]"; }
}
