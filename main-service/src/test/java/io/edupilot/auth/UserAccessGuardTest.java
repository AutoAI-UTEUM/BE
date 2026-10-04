package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

class UserAccessGuardTest {
	@Test
	void everyRequestReadsCurrentDatabaseStateWithoutAnInvalidationNotification() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository);
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
		UserAccessGuard guard = new UserAccessGuard(repository);
		when(repository.findAccessStateById(1L))
			.thenReturn(Optional.of(new UserAccessState(UserRole.ADMIN, UserStatus.ACTIVE)));
		assertThat(guard.check(new AuthenticatedUser(1L, UserRole.LEARNER)))
			.isEqualTo(ErrorCode.TOKEN_INVALID);
	}

	@Test
	void deletedAndAbsentUsersCannotAuthenticate() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository);
		when(repository.findAccessStateById(1L)).thenReturn(
			Optional.of(new UserAccessState(UserRole.LEARNER, UserStatus.DELETED)), Optional.empty());
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.TOKEN_INVALID);
	}
}
