package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupRole;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
	private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

	@Mock
	private UserRepository userRepository;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@Mock
	private RefreshTokenService refreshTokenService;

	@Mock
	private GoogleIdTokenVerifier googleIdTokenVerifier;

	@Mock
	private GoogleAccountService googleAccountService;

	private BCryptPasswordEncoder passwordEncoder;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		passwordEncoder = new BCryptPasswordEncoder();
		authService = new AuthService(
			userRepository,
			passwordEncoder,
			jwtTokenProvider,
			refreshTokenService,
			googleIdTokenVerifier,
			googleAccountService,
			Clock.fixed(NOW, ZoneOffset.UTC)
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
			" 홍길동 ",
			SignupRole.LEARNER
		));

		assertThat(response.userId()).isEqualTo(1L);
		assertThat(response.email()).isEqualTo("user@example.com");
		assertThat(response.name()).isEqualTo("홍길동");
		assertThat(response.role()).isEqualTo(UserRole.LEARNER);
		assertThat(response.affiliation()).isNull();
		assertThat(response.avatarUrl()).isNull();
		assertThat(response.learningEmailOptIn()).isFalse();
		verify(userRepository).saveAndFlush(any(User.class));
	}

	@Test
	void signupPersistsOptionalProfileAndAcceptedConsentVersions() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 3L);
			return user;
		});

		var response = authService.signup(new SignupRequest(
			"user@example.com",
			"password123",
			"홍길동",
			SignupRole.LEARNER,
			" EduPilot University ",
			true,
			"2026-07-01",
			"2026-07-01"
		));

		assertThat(response.affiliation()).isEqualTo("EduPilot University");
		assertThat(response.learningEmailOptIn()).isTrue();
		org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(
			User.class
		);
		verify(userRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getTermsVersion()).isEqualTo("2026-07-01");
		assertThat(captor.getValue().getPrivacyVersion()).isEqualTo("2026-07-01");
		assertThat(captor.getValue().getConsentedAt()).isEqualTo(NOW);
	}

	@Test
	void signupRejectsPartialOrUnknownConsentVersions() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(false);

		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"user@example.com",
				"password123",
				"홍길동",
				SignupRole.LEARNER,
				null,
				false,
				"2026-07-01",
				null
			)),
			ErrorCode.VALIDATION_FAILED
		);
		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"user@example.com",
				"password123",
				"홍길동",
				SignupRole.LEARNER,
				null,
				false,
				"2026-08-01",
				"2026-08-01"
			)),
			ErrorCode.VALIDATION_FAILED
		);
	}

	@Test
	void signupPersistsInstructorRole() {
		when(userRepository.existsByEmail("instructor@example.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 2L);
			return user;
		});

		var response = authService.signup(new SignupRequest(
			"instructor@example.com",
			"password123",
			"강사",
			SignupRole.INSTRUCTOR
		));

		assertThat(response.role()).isEqualTo(UserRole.INSTRUCTOR);
	}

	@Test
	void signupRejectsExistingOrRacingDuplicateEmail() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(true);
		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"user@example.com",
				"password123",
				"홍길동",
				SignupRole.LEARNER
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
				"홍길동",
				SignupRole.LEARNER
			)),
			ErrorCode.EMAIL_ALREADY_EXISTS
		);
	}

	@Test
	void emailAvailabilitySharesSignupNormalizationAndDuplicateLookup() {
		when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

		assertThat(authService.emailAvailability("  USER@Example.COM ").available())
			.isFalse();
		assertBusinessError(
			() -> authService.signup(new SignupRequest(
				"  USER@Example.COM ",
				"password123",
				"홍길동",
				SignupRole.LEARNER
			)),
			ErrorCode.EMAIL_ALREADY_EXISTS
		);

		when(userRepository.existsByEmail("withdrawn@example.com")).thenReturn(false);
		assertThat(authService.emailAvailability("withdrawn@example.com").available())
			.isTrue();
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
	void googleLoginVerifiesTokenAndIssuesSameLoginContract() {
		GoogleLoginRequest request = new GoogleLoginRequest(
			"id-token",
			null,
			null,
			null,
			null,
			null
		);
		GoogleProfile profile = new GoogleProfile(
			"google-subject",
			"user@example.com",
			"구글 사용자"
		);
		User user = user(5L, "user@example.com", "unused-password");
		when(googleIdTokenVerifier.verify("id-token")).thenReturn(profile);
		when(googleAccountService.resolve(request, profile)).thenReturn(user);
		when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");
		when(jwtTokenProvider.accessTokenExpiresInSeconds()).thenReturn(3600L);
		when(refreshTokenService.issue(user)).thenReturn("refresh-token");

		var result = authService.googleLogin(request);

		assertThat(result.response().accessToken()).isEqualTo("access-token");
		assertThat(result.response().user().id()).isEqualTo(5L);
		assertThat(result.refreshToken()).isEqualTo("refresh-token");
		verify(googleIdTokenVerifier).verify("id-token");
		verify(googleAccountService).resolve(request, profile);
	}

	@Test
	void googleOnlyPasswordSentinelCannotAuthenticateWithPassword() {
		User user = User.createGoogle(
			"google@example.com",
			"!oauth:google",
			"구글 사용자",
			UserRole.LEARNER,
			null,
			false,
			"2026-07-01",
			"2026-07-01",
			NOW,
			"google-subject"
		);
		ReflectionTestUtils.setField(user, "id", 6L);
		when(userRepository.findByEmail("google@example.com"))
			.thenReturn(Optional.of(user));

		assertBusinessError(
			() -> authService.login(new LoginRequest(
				"google@example.com",
				"password123"
			)),
			ErrorCode.INVALID_CREDENTIALS
		);
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
