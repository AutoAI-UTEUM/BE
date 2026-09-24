package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	@Mock private UserRepository users;
	@Mock private PasswordResetTokenRepository tokens;
	@Mock private RefreshTokenService refreshTokens;
	@Mock private EmailService emailService;
	@Mock private Validator validator;

	private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
	private PasswordResetService service;
	private User user;

	@BeforeEach
	void setUp() {
		user = User.create("known@example.com", encoder.encode("password123"), "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
		when(users.findByEmailForUpdate(anyString())).thenAnswer(invocation ->
			"known@example.com".equals(invocation.getArgument(0))
				? Optional.of(user) : Optional.empty()
		);
		service = new PasswordResetService(
			users, tokens, new PasswordResetRateLimiter(), encoder, refreshTokens,
			emailService,
			new EmailTemplates(new MailProperties(
				true, "logging", "no-reply@uteum.com", "", "https://dev.uteum.com",
				"ap-northeast-2"
			)), validator,
			Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	void mailFailureLeavesRequestFailSoftAndTokenHasOnlyHash() {
		when(emailService.sendAsync(any(EmailMessage.class)))
			.thenThrow(new IllegalStateException("provider unavailable"));
		assertThatCode(() -> service.request("known@example.com", "192.0.2.20"))
			.doesNotThrowAnyException();
		org.mockito.ArgumentCaptor<PasswordResetToken> captured =
			org.mockito.ArgumentCaptor.forClass(PasswordResetToken.class);
		verify(tokens).saveAndFlush(captured.capture());
		assertThat(captured.getValue().getTokenHash()).hasSize(64)
			.matches("[0-9a-f]{64}");
		assertThat(captured.getValue().getRequestedIp()).isEqualTo("192.0.2.20");
	}

	@Test
	void accountMissingStillDoesBcryptWorkWithoutSavingOrSending() {
		long missingStarted = System.nanoTime();
		service.request("missing@example.com", "192.0.2.21");
		long missingElapsed = System.nanoTime() - missingStarted;
		verifyNoInteractions(tokens, emailService);

		long knownStarted = System.nanoTime();
		service.request("known@example.com", "192.0.2.22");
		long knownElapsed = System.nanoTime() - knownStarted;
		verify(encoder, times(2)).matches(
			org.mockito.ArgumentMatchers.eq("password-reset-timing"), anyString()
		);
		long slower = Math.max(missingElapsed, knownElapsed);
		long faster = Math.min(missingElapsed, knownElapsed);
		assertThat(slower).isLessThanOrEqualTo(
			faster * 2 + Duration.ofMillis(100).toNanos()
		);
		verify(emailService, times(1)).sendAsync(any(EmailMessage.class));
	}
}
