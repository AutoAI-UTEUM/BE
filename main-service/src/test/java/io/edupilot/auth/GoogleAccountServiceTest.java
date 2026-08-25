package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class GoogleAccountServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
	private static final GoogleProfile PROFILE = new GoogleProfile(
		"google-subject",
		"USER@Example.com",
		"구글 사용자"
	);

	@Mock
	private UserRepository userRepository;

	private GoogleAccountService service;

	@BeforeEach
	void setUp() {
		service = new GoogleAccountService(
			userRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void newGoogleProfileCreatesGoogleUserWithConsent() {
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 1L);
			return user;
		});

		User user = service.resolve(completeRequest(), PROFILE);

		assertThat(user.getEmail()).isEqualTo("user@example.com");
		assertThat(user.getName()).isEqualTo("구글 사용자");
		assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
		assertThat(user.getGoogleSub()).isEqualTo("google-subject");
		assertThat(user.getPasswordHash()).isEqualTo("!oauth:google");
		assertThat(user.getTermsVersion()).isEqualTo("2026-07-01");
		assertThat(user.getPrivacyVersion()).isEqualTo("2026-07-01");
		assertThat(user.getConsentedAt()).isEqualTo(NOW);
		assertThat(user.isLearningEmailOptIn()).isTrue();
		assertThat(user.getAffiliation()).isEqualTo("EduPilot University");
	}

	@Test
	void existingGoogleSubjectReturnsSameUserWithoutDuplicateSignup() {
		User existing = googleUser(7L);
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.of(existing));

		User user = service.resolve(minimalRequest(), PROFILE);

		assertThat(user).isSameAs(existing);
		verify(userRepository, never()).findByEmail(any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void retryingSameGoogleRequestLogsInCreatedUserWithoutDuplicateSignup() {
		AtomicReference<User> stored = new AtomicReference<>();
		when(userRepository.findByGoogleSub("google-subject"))
			.thenAnswer(invocation -> Optional.ofNullable(stored.get()));
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 11L);
			stored.set(user);
			return user;
		});

		User created = service.resolve(completeRequest(), PROFILE);
		User retried = service.resolve(completeRequest(), PROFILE);

		assertThat(retried).isSameAs(created);
		verify(userRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
	}

	@Test
	void existingLocalEmailIsLinkedWithoutChangingOriginProvider() {
		User local = User.create("user@example.com", "encoded-password", "로컬 사용자");
		ReflectionTestUtils.setField(local, "id", 3L);
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.of(local));

		User user = service.resolve(minimalRequest(), PROFILE);

		assertThat(user).isSameAs(local);
		assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
		assertThat(user.getGoogleSub()).isEqualTo("google-subject");
		verify(userRepository).flush();
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void newProfileWithoutRoleOrConsentRequiresSignupDetails() {
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.empty());

		assertBusinessError(
			() -> service.resolve(minimalRequest(), PROFILE),
			ErrorCode.SIGNUP_REQUIRED
		);
	}

	@Test
	void withdrawalClearsGoogleSubjectForFutureSignup() {
		User user = googleUser(9L);

		user.withdraw();

		assertThat(user.getGoogleSub()).isNull();
		assertThat(user.isActive()).isFalse();
	}

	private GoogleLoginRequest completeRequest() {
		return new GoogleLoginRequest(
			"id-token",
			"LEARNER",
			"2026-07-01",
			"2026-07-01",
			true,
			" EduPilot University "
		);
	}

	private GoogleLoginRequest minimalRequest() {
		return new GoogleLoginRequest("id-token", null, null, null, null, null);
	}

	private User googleUser(Long id) {
		User user = User.createGoogle(
			"user@example.com",
			"!oauth:google",
			"구글 사용자",
			io.edupilot.user.UserRole.LEARNER,
			null,
			false,
			"2026-07-01",
			"2026-07-01",
			NOW,
			"google-subject"
		);
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
