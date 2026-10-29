package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;

class GuardianTeamAccessStateTest {
	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String POLICY_DIGEST = "a".repeat(64);

	@Test
	void explicitTeamApprovalNeedsEnabledModeVerifiedEmailValidDobAndUnexpiredGrant() {
		User child = child();
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), true);
		child.recordGuardianTeamPolicyDigest(POLICY_DIGEST);
		assertThat(UserBusinessAccessState.from(child).eligibilityFailure(CLOCK, false))
			.isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(UserBusinessAccessState.from(child).eligibilityFailure(CLOCK, true, POLICY_DIGEST)).isNull();
		assertThat(UserBusinessAccessState.from(child).eligibilityFailure(Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC), true, POLICY_DIGEST))
			.isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		ReflectionTestUtils.setField(child, "emailVerifiedAt", null);
		assertThat(UserBusinessAccessState.from(child).eligibilityFailure(CLOCK, true))
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		child.verifyEmail(NOW);
		ReflectionTestUtils.setField(child, "dateOfBirth", LocalDate.of(2027, 1, 1));
		assertThat(UserBusinessAccessState.from(child).eligibilityFailure(CLOCK, true))
			.isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
	}

	@Test
	void serviceApprovalDoesNotInferOptionalExternalAiConsent() {
		User child = child();
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), false);
		child.recordGuardianTeamPolicyDigest(POLICY_DIGEST);
		var state = UserBusinessAccessState.from(child);
		assertThat(state.eligibilityFailure(CLOCK, true, POLICY_DIGEST)).isNull();
		assertThat(state.aiEligibilityFailure(CLOCK, true, POLICY_DIGEST)).isEqualTo(ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED);
	}

	@Test
	void revocationAndReapprovalInvalidatePreviouslyCapturedAiGeneration() {
		User child = child();
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), true);
		long firstGeneration = child.getGuardianConsentEpoch();
		child.clearGuardianTeamApproval(false);
		assertThat(UserBusinessAccessState.from(child).aiEligibilityFailure(CLOCK, true))
			.isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), true);
		child.recordGuardianTeamPolicyDigest(POLICY_DIGEST);
		UserRepository users = mock(UserRepository.class);
		when(users.findBusinessAccessStateById(1L)).thenReturn(Optional.of(UserBusinessAccessState.from(child)));
		UserAccessGuard guard = new UserAccessGuard(users, CLOCK, true, POLICY_DIGEST);
		var principal = new AuthenticatedUser(1L, UserRole.LEARNER);
		assertThat(guard.checkAiBusiness(principal)).isNull();
		assertThat(guard.checkAiBusiness(principal, firstGeneration)).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED);
		assertThat(guard.checkAiBusiness(principal, child.getGuardianConsentEpoch())).isNull();
	}

	@Test
	void withdrawalClearsGrantAndChangesGenerationWithoutPublishingGuardianFields() {
		User child = child();
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), true);
		child.recordGuardianTeamPolicyDigest(POLICY_DIGEST);
		long before = child.getGuardianConsentEpoch();
		child.withdraw();
		assertThat(child.getGuardianConsentEpoch()).isGreaterThan(before);
		assertThat(child.getGuardianApprovedUntil()).isNull();
		assertThat(child.getGuardianApprovalPolicyDigest()).isNull();
		assertThat(child.isGuardianAiConsentAllowed()).isFalse();
		assertThat(child.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
		assertThat(child.getDateOfBirth()).isNull();
		assertThat(UserBusinessAccessState.from(child).toString()).doesNotContain("2012", "TEAM_APPROVED");
	}

	@Test
	void legacyAndYearFifteenPolicyRemainIndependentOfGuardianMode() {
		User older = User.create("synthetic@example.invalid", "hash", "합성 계정");
		older.recordSignupDateOfBirth(LocalDate.of(2011, 12, 31));
		older.verifyEmail(NOW);
		assertThat(UserBusinessAccessState.from(older).aiEligibilityFailure(CLOCK, false)).isNull();
		User legacy = User.create("legacy@example.invalid", "hash", "합성 기존 계정");
		ReflectionTestUtils.setField(legacy, "accessCohort", AccountAccessCohort.LEGACY_EXEMPT);
		assertThat(UserBusinessAccessState.from(legacy).aiEligibilityFailure(CLOCK, false)).isNull();
		assertThatThrownBy(() -> legacy.recordGuardianTeamApproval(NOW.plusSeconds(60), true))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void changedOrMissingPolicyDigestBlocksBusinessAndExternalAiWithoutInferringConsent() {
		User child = child();
		child.recordGuardianTeamApproval(NOW.plusSeconds(60), true);
		child.recordGuardianTeamPolicyDigest(POLICY_DIGEST);
		var state = UserBusinessAccessState.from(child);
		assertThat(state.eligibilityFailure(CLOCK, true)).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(state.eligibilityFailure(CLOCK, true, "b".repeat(64))).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(state.aiEligibilityFailure(CLOCK, true, "b".repeat(64))).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(state.aiEligibilityFailure(CLOCK, true, POLICY_DIGEST)).isNull();
	}

	private User child() {
		User child = User.create("child@example.invalid", "hash", "합성 아동");
		child.recordSignupDateOfBirth(LocalDate.of(2012, 1, 1));
		child.verifyEmail(NOW);
		return child;
	}
}
