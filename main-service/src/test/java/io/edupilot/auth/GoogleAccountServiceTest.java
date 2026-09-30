package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.policy.PolicyService;
import io.edupilot.policy.PolicyService.SignupSelection;
import io.edupilot.policy.PolicyType;
import io.edupilot.policy.dto.PolicyConsentChoice;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

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

	@Mock
	private PolicyService policyService;

	private GoogleAccountService service;

	@BeforeEach
	void setUp() {
		service = new GoogleAccountService(userRepository, policyService);
		org.mockito.Mockito.lenient().when(policyService.validateSignup(any())).thenReturn(
			new SignupSelection("0.9", "0.9", NOW));
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

		User user = resolve(completeRequest(), PROFILE);

		assertThat(user.getEmail()).isEqualTo("user@example.com");
		assertThat(user.getName()).isEqualTo("구글 사용자");
		assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
		assertThat(user.getGoogleSub()).isEqualTo("google-subject");
		assertThat(user.getPasswordHash()).isEqualTo("!oauth:google");
		assertThat(user.getTermsVersion()).isEqualTo("0.9");
		assertThat(user.getPrivacyVersion()).isEqualTo("0.9");
		assertThat(user.getConsentedAt()).isEqualTo(NOW);
		assertThat(user.isLearningEmailOptIn()).isTrue();
		assertThat(user.getAffiliation()).isEqualTo("EduPilot University");
		verify(policyService).recordSignup(any(User.class), any(SignupSelection.class),
			org.mockito.ArgumentMatchers.eq("192.0.2.1"),
			org.mockito.ArgumentMatchers.eq("test-agent"));
	}

	@Test
	void existingGoogleSubjectReturnsSameUserWithoutDuplicateSignup() {
		User existing = googleUser(7L);
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.of(existing));

		User user = resolve(minimalRequest(), PROFILE);

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

		User created = resolve(completeRequest(), PROFILE);
		User retried = resolve(completeRequest(), PROFILE);

		assertThat(retried).isSameAs(created);
		verify(userRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
	}

	@Test
	void existingLocalEmailIsRejectedWithoutLinkingOrChangingCredentials() {
		User local = User.create("user@example.com", "encoded-password", "로컬 사용자");
		ReflectionTestUtils.setField(local, "id", 3L);
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.of(local));

		assertBusinessError(() -> resolve(minimalRequest(), PROFILE),
			ErrorCode.EMAIL_ALREADY_EXISTS);

		assertThat(local.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
		assertThat(local.getGoogleSub()).isNull();
		assertThat(local.getPasswordHash()).isEqualTo("encoded-password");
		verify(userRepository, never()).flush();
		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(policyService);
	}

	@Test
	void differentGoogleSubjectWithSameEmailCannotReplaceExistingSubject() {
		User existing = googleUser(7L);
		GoogleProfile other = new GoogleProfile("different-subject", PROFILE.email(), PROFILE.name());
		when(userRepository.findByGoogleSub(other.sub())).thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.of(existing));

		assertBusinessError(() -> resolve(completeRequest(), other),
			ErrorCode.EMAIL_ALREADY_EXISTS);

		assertThat(existing.getGoogleSub()).isEqualTo("google-subject");
		verify(userRepository, never()).flush();
		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(policyService);
	}

	@ParameterizedTest
	@CsvSource({"DELETED,true", "SUSPENDED,true", "DELETED,false", "SUSPENDED,false"})
	void inactiveAccountRemainsRejectedBeforeTokenIssuance(UserStatus status, boolean sameSubject) {
		User existing = googleUser(7L);
		ReflectionTestUtils.setField(existing, "status", status);
		when(userRepository.findByGoogleSub(PROFILE.sub()))
			.thenReturn(sameSubject ? Optional.of(existing) : Optional.empty());
		if (!sameSubject) {
			when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(existing));
		}

		assertBusinessError(() -> resolve(minimalRequest(), PROFILE),
			status == UserStatus.SUSPENDED ? ErrorCode.ACCOUNT_SUSPENDED : ErrorCode.USER_INACTIVE);

		verify(userRepository, never()).flush();
		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(policyService);
	}

	@Test
	void concurrentSignupConstraintFailureDoesNotFallBackToEmailLinking() {
		when(userRepository.findByGoogleSub(PROFILE.sub())).thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("unique constraint"));

		assertBusinessError(() -> resolve(completeRequest(), PROFILE),
			ErrorCode.EMAIL_ALREADY_EXISTS);

		verify(userRepository, never()).flush();
		verify(policyService, never()).recordSignup(any(), any(), any(), any());
	}

	@Test
	void newProfileWithoutRoleOrConsentRequiresSignupDetails() {
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.empty());

		assertBusinessError(
			() -> resolve(minimalRequest(), PROFILE),
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
			List.of(new PolicyConsentChoice(PolicyType.TERMS, "0.9"),
				new PolicyConsentChoice(PolicyType.PRIVACY, "0.9")),
			true,
			" EduPilot University "
		);
	}

	private GoogleLoginRequest minimalRequest() {
		return new GoogleLoginRequest("id-token", null, null, null, null);
	}

	@Test
	void newGoogleProfileWithRoleButNoCurrentConsentIsRejected() {
		when(userRepository.findByGoogleSub("google-subject"))
			.thenReturn(Optional.empty());
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.empty());
		when(policyService.validateSignup(any())).thenThrow(
			new BusinessException(ErrorCode.POLICY_CONSENT_REQUIRED));

		assertBusinessError(() -> resolve(new GoogleLoginRequest(
			"id-token", "LEARNER", null, false, null), PROFILE),
			ErrorCode.POLICY_CONSENT_REQUIRED);
		verify(userRepository, never()).saveAndFlush(any());
	}

	private User resolve(GoogleLoginRequest request, GoogleProfile profile) {
		return service.resolve(request, profile, "192.0.2.1", "test-agent");
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
