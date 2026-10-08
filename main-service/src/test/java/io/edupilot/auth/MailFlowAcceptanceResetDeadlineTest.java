package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailOutboxWorker;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import jakarta.validation.Validator;

/** Executable regression: PR533 reset issuance must not extend a token's deadline while persisting it. */
@ExtendWith(MockitoExtension.class)
class MailFlowAcceptanceResetDeadlineTest {
	private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
	@Mock private UserRepository users;
	@Mock private EmailVerificationTokenRepository verificationTokens;
	@Mock private PasswordResetTokenRepository resetTokens;
	@Mock private RefreshTokenService refreshTokens;
	@Mock private PasswordEncoder passwords;
	@Mock private Validator validator;
	@Mock private EmailDeliveryStore history;
	@Mock private EmailOutboxStore outbox;
	@Mock private EmailOutboxWorker worker;
	@Mock private Clock clock;
	private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
	private EmailService mail;
	private EmailTemplates templates;
	private User account;

	@BeforeEach
	void setup() {
		when(clock.instant()).thenAnswer(invocation -> time.get());
		when(history.queue(any())).thenReturn(1L);
		MailProperties properties = new MailProperties(true, "logging", "synthetic@example.test", "",
			"https://mail-flow.example.test", "ap-northeast-2");
		mail = new EmailService(history, outbox, worker, properties, clock);
		templates = new EmailTemplates(properties);
		account = User.create("deadline@example.test", "!synthetic", "Synthetic learner");
		ReflectionTestUtils.setField(account, "id", 1L);
	}

	@Test
	void verificationQueueKeepsTheTokenDeadlineAfterNinetySecondsOfPersistenceDelay() {
		when(verificationTokens.saveAndFlush(any())).thenAnswer(invocation -> {
			time.set(NOW.plusSeconds(90));
			return invocation.getArgument(0);
		});
		new EmailVerificationService(users, verificationTokens, new EmailVerificationRateLimiter(),
			mail, templates, clock).signup(account, "192.0.2.1");

		var token = ArgumentCaptor.forClass(EmailVerificationToken.class);
		verify(verificationTokens).saveAndFlush(token.capture());
		assertThat(queuedExpiry()).isEqualTo(token.getValue().getExpiresAt());
	}

	@Test
	void resetQueueMustKeepTheTokenDeadlineAfterNinetySecondsOfPersistenceDelay() {
		when(users.findByEmailForUpdate(account.getEmail())).thenReturn(Optional.of(account));
		when(resetTokens.saveAndFlush(any())).thenAnswer(invocation -> {
			time.set(NOW.plusSeconds(90));
			return invocation.getArgument(0);
		});
		new PasswordResetService(users, resetTokens, new PasswordResetRateLimiter(), passwords,
			refreshTokens, mail, templates, validator, clock).request(account.getEmail(), "192.0.2.1");

		var token = ArgumentCaptor.forClass(PasswordResetToken.class);
		verify(resetTokens).saveAndFlush(token.capture());
		assertThat(token.getValue().isUsable(NOW.plusSeconds(1800))).isFalse();
		assertThat(queuedExpiry()).as("reset payload deadline must equal the persisted token deadline")
			.isEqualTo(token.getValue().getExpiresAt());
	}

	private Instant queuedExpiry() {
		var expiry = ArgumentCaptor.forClass(Instant.class);
		verify(outbox).enqueue(eq(1L), any(EmailMessage.class), expiry.capture());
		return expiry.getValue();
	}
}
