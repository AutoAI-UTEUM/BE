package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private UserWithdrawalHook withdrawalHook;

	private BCryptPasswordEncoder passwordEncoder;
	private UserService userService;
	private User user;

	@BeforeEach
	void setUp() {
		passwordEncoder = new BCryptPasswordEncoder();
		userService = new UserService(
			userRepository,
			passwordEncoder,
			refreshTokenService,
			List.of(withdrawalHook)
		);
		user = User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"홍길동"
		);
		ReflectionTestUtils.setField(user, "id", 1L);
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
	}

	@Test
	void withdrawalAnonymizesUserAndInvokesHooksAndTokenRevocation() {
		userService.withdraw(1L, "password123");

		assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(user.getEmail()).isEqualTo("deleted_1");
		assertThat(user.getName()).isEqualTo("탈퇴 사용자");
		assertThat(user.getPasswordHash()).isEqualTo("!withdrawn:1");
		InOrder order = inOrder(withdrawalHook, refreshTokenService);
		order.verify(withdrawalHook).onWithdraw(1L);
		order.verify(refreshTokenService).revokeAll(1L);
	}

	@Test
	void withdrawalRejectsWrongPasswordWithoutChangingUser() {
		assertThatThrownBy(() -> userService.withdraw(1L, "wrong"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.INVALID_CREDENTIALS)
			);

		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		verify(userRepository).findById(1L);
	}
}
