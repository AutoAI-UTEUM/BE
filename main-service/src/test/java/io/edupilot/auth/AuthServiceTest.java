package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.RefreshTokenService.RotationResult;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private RefreshTokenService refreshTokenService;

	private BCryptPasswordEncoder passwordEncoder;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		passwordEncoder = new BCryptPasswordEncoder();
		authService = new AuthService(
			userRepository,
			passwordEncoder,
			jwtTokenProvider,
			refreshTokenService
		);
	}

	@Test
	void signupNormalizesEmailAndHashesPassword() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 1L);
			return user;
		});

		var response = authService.signup(new SignupRequest(
			"  USER@Example.COM ",
			"password123",
			" 홍길동 "
		));

		assertThat(response.userId()).isEqualTo(1L);
		assertThat(response.email()).isEqualTo("user@example.com");
		assertThat(response.name()).isEqualTo("홍길동");
		verify(userRepository).saveAndFlush(any(User.class));
	}

	@Test
	void signupRejectsExistingOrRacingDuplicateEmail() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"user@example.com",
				"password123",
				"홍길동"
			)),
			ErrorCode.EMAIL_ALREADY_EXISTS
		);

		when(userRepository.existsByEmail("other@example.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("duplicate"));
		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"other@example.com",
				"password123",
				"홍길동"
			)),
			ErrorCode.EMAIL_ALREADY_EXISTS
		);
	}

	@Test
	void loginReturnsAccessAndRefreshForActiveUser() {
		User user = user(1L, "user@example.com", "password123");
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");
		when(jwtTokenProvider.accessTokenExpiresInSeconds()).thenReturn(3600L);
		when(refreshTokenService.issue(user)).thenReturn("refresh-token");

		var result = authService.login(new LoginRequest(
			"USER@example.com",
			"password123"
		));

		assertThat(result.response().accessToken()).isEqualTo("access-token");
		assertThat(result.response().user().id()).isEqualTo(1L);
		assertThat(result.refreshToken()).isEqualTo("refresh-token");
	}

	@Test
	void loginHidesMissingUserAndWrongPasswordBehindSameError() {
		when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
		assertBusinessError(
			() -> authService.login(new LoginRequest(
				"missing@example.com",
				"password123"
			)),
			ErrorCode.INVALID_CREDENTIALS
		);

		User user = user(1L, "user@example.com", "password123");
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		assertBusinessError(
			() -> authService.login(new LoginRequest(
				"user@example.com",
				"wrong-password"
			)),
			ErrorCode.INVALID_CREDENTIALS
		);
	}

	@Test
	void loginRejectsWithdrawnUser() {
		User user = user(1L, "user@example.com", "password123");
		user.withdraw();
		when(userRepository.findByEmail("deleted_1")).thenReturn(Optional.of(user));

		assertBusinessError(
			() -> authService.login(new LoginRequest("deleted_1", "password123")),
			ErrorCode.USER_INACTIVE
		);
	}

	@Test
	void refreshMapsCommittedRotationResultToStableErrors() {
		when(refreshTokenService.rotate("invalid")).thenReturn(RotationResult.invalid());
		assertBusinessError(
			() -> authService.refresh("invalid"),
			ErrorCode.TOKEN_INVALID
		);

		when(refreshTokenService.rotate("inactive")).thenReturn(RotationResult.inactive());
		assertBusinessError(
			() -> authService.refresh("inactive"),
			ErrorCode.USER_INACTIVE
		);
	}

	private User user(Long id, String email, String password) {
		User user = User.create(email, passwordEncoder.encode(password), "홍길동");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private void assertBusinessError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
