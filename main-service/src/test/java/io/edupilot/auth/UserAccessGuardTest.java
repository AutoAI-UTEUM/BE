package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.user.AccountAccessCohort;
import io.edupilot.user.EmailVerificationState;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

class UserAccessGuardTest {
	@Test
	void everyRequestReadsCurrentDatabaseStateWithoutAnInvalidationNotification() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		when(repository.findAccessStateById(1L)).thenReturn(
			Optional.of(new UserAccessState(UserRole.LEARNER, UserStatus.ACTIVE)),
			Optional.of(new UserAccessState(UserRole.LEARNER, UserStatus.SUSPENDED)));
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);

		assertThat(guard.check(principal)).isNull();
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
		verify(repository, times(2)).findAccessStateById(1L);
	}

	@Test
	void changedRoleInvalidatesOldJwtAuthority() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		when(repository.findAccessStateById(1L))
			.thenReturn(Optional.of(new UserAccessState(UserRole.ADMIN, UserStatus.ACTIVE)));
		assertThat(guard.check(new AuthenticatedUser(1L, UserRole.LEARNER)))
			.isEqualTo(ErrorCode.TOKEN_INVALID);
	}

	@Test
	void deletedAndAbsentUsersCannotAuthenticate() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		when(repository.findAccessStateById(1L)).thenReturn(
			Optional.of(new UserAccessState(UserRole.LEARNER, UserStatus.DELETED)), Optional.empty());
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
	}

	@Test
	void authenticatingAnUnconfirmedNewAccountDoesNotGrantBusinessAccess() {
		UserRepository repository = mock(UserRepository.class);
		when(repository.findAccessStateById(1L)).thenReturn(Optional.of(new UserAccessState(UserRole.LEARNER, UserStatus.ACTIVE)));
		when(repository.findBusinessAccessStateById(1L)).thenReturn(Optional.of(new UserBusinessAccessState(
			UserRole.LEARNER, UserStatus.ACTIVE, EmailVerificationState.VERIFIED, Instant.EPOCH,
			AccountAccessCohort.NEW_SIGNUP, AgeVerificationState.UNKNOWN, null)));
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);
		assertThat(guard.check(principal)).isNull();
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
	}

	@Test
	void everyBusinessCheckReadsFreshEligibilityAndRequiresEmailEvidenceFirst() {
		UserRepository repository = mock(UserRepository.class);
		when(repository.findBusinessAccessStateById(1L)).thenReturn(
			Optional.of(new UserBusinessAccessState(UserRole.ADMIN, UserStatus.ACTIVE,
				EmailVerificationState.UNKNOWN, null, AccountAccessCohort.LEGACY_EXEMPT, AgeVerificationState.UNKNOWN, null)),
			Optional.of(new UserBusinessAccessState(UserRole.ADMIN, UserStatus.ACTIVE,
				EmailVerificationState.VERIFIED, Instant.EPOCH, AccountAccessCohort.NEW_SIGNUP, AgeVerificationState.MANUAL_PENDING, null)),
			Optional.of(new UserBusinessAccessState(UserRole.ADMIN, UserStatus.ACTIVE,
				EmailVerificationState.VERIFIED, null, AccountAccessCohort.NEW_SIGNUP, AgeVerificationState.MANUAL_PENDING, null)));
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.ADMIN);
		assertThat(guard.checkBusiness(principal)).isNull();
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.GUARDIAN_VERIFICATION_PENDING);
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		verify(repository, times(3)).findBusinessAccessStateById(1L);
	}

	@Test
	void accountAndRoleRevocationStillTakePrecedenceAtTheBusinessBoundary() {
		UserRepository repository = mock(UserRepository.class);
		when(repository.findBusinessAccessStateById(1L)).thenReturn(
			Optional.of(new UserBusinessAccessState(UserRole.LEARNER, UserStatus.SUSPENDED,
				EmailVerificationState.UNKNOWN, null, AccountAccessCohort.NEW_SIGNUP, AgeVerificationState.UNKNOWN, null)),
			Optional.of(new UserBusinessAccessState(UserRole.LEARNER, UserStatus.DELETED,
				EmailVerificationState.UNKNOWN, null, AccountAccessCohort.NEW_SIGNUP, AgeVerificationState.UNKNOWN, null)),
			Optional.of(new UserBusinessAccessState(UserRole.ADMIN, UserStatus.ACTIVE,
				EmailVerificationState.UNKNOWN, null, AccountAccessCohort.LEGACY_EXEMPT, AgeVerificationState.UNKNOWN, null)), Optional.empty());
		UserAccessGuard guard = new UserAccessGuard(repository, java.time.Clock.systemUTC());
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThat(guard.checkBusiness(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
	}
}
