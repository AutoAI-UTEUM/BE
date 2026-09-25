package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

class UserAccessGuardTest {
	@Test
	void statusIsCachedForOneMinuteAndInvalidationBlocksExistingTokenImmediately() {
		UserRepository repository = mock(UserRepository.class);
		AtomicLong nanos = new AtomicLong();
		UserAccessGuard guard = new UserAccessGuard(repository, nanos::get);
		User user = User.create("user@example.com", "hash", "user", UserRole.LEARNER);
		ReflectionTestUtils.setField(user, "id", 1L);
		when(repository.findById(1L)).thenReturn(Optional.of(user));
		AuthenticatedUser principal = new AuthenticatedUser(1L, UserRole.LEARNER);

		assertThat(guard.check(principal)).isNull();
		assertThat(guard.check(principal)).isNull();
		verify(repository, times(1)).findById(1L);

		user.suspend("운영자 확인", 2L, Instant.now());
		guard.invalidateAfterCommit(1L);
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
		nanos.addAndGet(Duration.ofMinutes(1).toNanos());
		assertThat(guard.check(principal)).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
	}

	@Test
	void changedRoleInvalidatesOldJwtAuthority() {
		UserRepository repository = mock(UserRepository.class);
		UserAccessGuard guard = new UserAccessGuard(repository, () -> 0);
		User user = User.create("user@example.com", "hash", "user", UserRole.LEARNER);
		ReflectionTestUtils.setField(user, "id", 1L);
		when(repository.findById(1L)).thenReturn(Optional.of(user));
		user.changeRole(UserRole.ADMIN);
		assertThat(guard.check(new AuthenticatedUser(1L, UserRole.LEARNER)))
			.isEqualTo(ErrorCode.TOKEN_INVALID);
	}
}
