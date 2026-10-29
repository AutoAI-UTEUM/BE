package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.AccountAccessCohort;
import io.edupilot.user.EmailVerificationState;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

class BirthdatePolicyTest {
	private static final Clock OCTOBER = at("2026-10-05T00:00:00Z");

	static Stream<Arguments> calendarBoundaries() {
		return Stream.of(
			Arguments.of("2026-10-04T14:59:59.999999999Z", "2026-10-04", "2026-10-05"),
			Arguments.of("2026-10-04T15:00:00Z", "2026-10-05", "2026-10-06"),
			Arguments.of("2026-12-31T14:59:59.999999999Z", "2026-12-31", "2027-01-01"),
			Arguments.of("2026-12-31T15:00:00Z", "2027-01-01", "2027-01-02"),
			Arguments.of("2028-02-28T15:00:00Z", "2028-02-29", "2028-03-01"),
			Arguments.of("2028-02-29T15:00:00Z", "2028-03-01", "2028-03-02"));
	}

	@ParameterizedTest @MethodSource("calendarBoundaries")
	void rejectsOnlyFutureDatesOnTheKoreanCalendarRegardlessOfClockZone(String instant, String today, String future) {
		for (ZoneId zone : new ZoneId[]{ZoneOffset.UTC, ZoneId.of("America/Los_Angeles"), ZoneId.of("Asia/Seoul")}) {
			Clock clock = Clock.fixed(Instant.parse(instant), zone);
			assertThat(BirthdatePolicy.today(clock)).isEqualTo(LocalDate.parse(today));
			BirthdatePolicy.validate(LocalDate.parse(today), clock);
			assertInvalid(LocalDate.parse(future), clock);
		}
	}

	static Stream<Arguments> years() {
		return Stream.of(
			Arguments.of("2012-01-01", false), Arguments.of("2012-12-31", false),
			Arguments.of("2011-01-01", true), Arguments.of("2011-12-31", true),
			Arguments.of("2012-02-29", false), Arguments.of("2008-02-29", true),
			Arguments.of("2026-10-05", false), Arguments.of("2026-10-06", false));
	}

	@ParameterizedTest @MethodSource("years")
	void yearDifferenceIncludesEveryoneTurningFourteenAndDoesNotUseBirthdayOrLeapDay(String birthdate, boolean eligible) {
		assertThat(BirthdatePolicy.guardianNotRequired(LocalDate.parse(birthdate), OCTOBER)).isEqualTo(eligible);
	}

	@Test
	void transitionToYearDifferenceFifteenOccursAtKoreanNewYear() {
		LocalDate date = LocalDate.of(2012, 12, 31);
		assertThat(BirthdatePolicy.guardianNotRequired(date, at("2026-12-31T14:59:59.999999999Z"))).isFalse();
		assertThat(BirthdatePolicy.guardianNotRequired(date, at("2026-12-31T15:00:00Z"))).isTrue();
	}

	@Test
	void missingOutOfStorageBoundsAndFutureDatesNeverEnableNewAccounts() {
		for (LocalDate date : new LocalDate[]{null, LocalDate.of(0, 1, 1), LocalDate.of(10000, 1, 1), LocalDate.of(2027, 1, 1)}) {
			assertInvalid(date, OCTOBER);
			assertThat(BirthdatePolicy.guardianNotRequired(date, OCTOBER)).isFalse();
		}
	}

	static Stream<Arguments> rolesAndStates() {
		return Stream.of(UserRole.values()).flatMap(role -> Stream.of(AgeVerificationState.values()).map(state -> Arguments.of(role, state)));
	}

	@ParameterizedTest @MethodSource("rolesAndStates")
	void yearRuleHasNoRoleBypassAndDoesNotRequireOrForgeGuardianEvidence(UserRole role, AgeVerificationState state) {
		assertThat(snapshot(role, state, LocalDate.of(2012, 12, 31), AccountAccessCohort.NEW_SIGNUP, true).eligibilityFailure(OCTOBER))
			.isEqualTo(state == AgeVerificationState.MANUAL_PENDING ? ErrorCode.GUARDIAN_VERIFICATION_PENDING : ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(snapshot(role, state, LocalDate.of(2011, 12, 31), AccountAccessCohort.NEW_SIGNUP, true).eligibilityFailure(OCTOBER)).isNull();
		assertThat(snapshot(role, state, LocalDate.of(2011, 12, 31), AccountAccessCohort.NEW_SIGNUP, false).eligibilityFailure(OCTOBER))
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		assertThat(snapshot(role, state, null, AccountAccessCohort.LEGACY_EXEMPT, false).eligibilityFailure(OCTOBER)).isNull();
	}

	private static UserBusinessAccessState snapshot(UserRole role, AgeVerificationState state, LocalDate date, AccountAccessCohort cohort, boolean verified) {
		return new UserBusinessAccessState(role, UserStatus.ACTIVE, verified ? EmailVerificationState.VERIFIED : EmailVerificationState.UNKNOWN,
			verified ? Instant.EPOCH : null, cohort, state, date);
	}
	private static Clock at(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }
	private static void assertInvalid(LocalDate date, Clock clock) {
		assertThatThrownBy(() -> BirthdatePolicy.validate(date, clock)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}
}
