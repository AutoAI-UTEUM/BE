package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class EmailVerificationFragmentContractTest {
	private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
	@Mock private UserRepository users;
	@Mock private EmailVerificationTokenRepository tokens;
	@Mock private EmailVerificationRateLimiter limits;
	@Mock private EmailService mail;
	private EmailVerificationService verification;
	private User local;

	@BeforeEach
	void setup() {
		verification = service("https://dev.uteum.com");
		local = User.create("synthetic@example.test", "!synthetic", "Synthetic learner");
		ReflectionTestUtils.setField(local, "id", 1L);
	}

	@ParameterizedTest
	@EnumSource(AuthProvider.class)
	void everySignupProviderIssuesAFragmentLinkBoundToThePersistedHash(AuthProvider provider) {
		User account = provider == AuthProvider.LOCAL ? local : User.createGoogle("synthetic@example.test",
			"!synthetic-google", "Synthetic learner", UserRole.LEARNER, null, false, null, null, null, "synthetic-sub");
		ReflectionTestUtils.setField(account, "id", 1L);

		verification.signup(account, "192.0.2.1");

		assertLink("https://dev.uteum.com");
		assertThat(account.isEmailVerified()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://dev.uteum.com", "https://www.uteum.com/"})
	void textAndClickableHtmlPreserveTheConfiguredOriginAndExactFragment(String base) {
		service(base).signup(local, "192.0.2.1");

		assertLink(base);
	}

	@Test
	void manualRequestUsesTheSameFragmentContract() {
		when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(local));
		when(limits.allowRequest(1L, "192.0.2.1")).thenReturn(true);

		verification.request(1L, "192.0.2.1");

		assertLink("https://dev.uteum.com");
	}

	@Test
	void resendInvalidatesUnusedTokensAndIssuesANewFragmentWithoutReusingItsPredecessor() {
		when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(local));
		when(limits.allowRequest(1L, "192.0.2.1")).thenReturn(true);

		verification.request(1L, "192.0.2.1");
		verification.request(1L, "192.0.2.1");

		var messages = ArgumentCaptor.forClass(EmailMessage.class);
		verify(mail, times(2)).sendAsync(messages.capture(), eq(NOW.plusSeconds(1800)));
		verify(tokens, times(2)).invalidateUnused(1L, NOW);
		assertThat(messages.getAllValues().stream().map(this::link).map(URI::getRawFragment)).doesNotHaveDuplicates();
		assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(link(message).getRawQuery()).isNull());
	}

	private void assertLink(String base) {
		var message = ArgumentCaptor.forClass(EmailMessage.class);
		var persisted = ArgumentCaptor.forClass(EmailVerificationToken.class);
		verify(mail).sendAsync(message.capture(), eq(NOW.plusSeconds(1800)));
		verify(tokens).saveAndFlush(persisted.capture());
		URI uri = link(message.getValue());
		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getHost()).isEqualTo(URI.create(base).getHost());
		assertThat(uri.getPath()).isEqualTo("/verify-email");
		assertThat(uri.getRawQuery()).isNull();
		assertThat(uri.getRawFragment()).matches("token=[A-Za-z0-9_-]{43}");
		String raw = uri.getRawFragment().substring("token=".length());
		assertThat(persisted.getValue().getTokenHash()).isEqualTo(EmailVerificationService.hash(raw));
		assertThat(persisted.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(1800));
		assertThat(message.getValue().htmlBody()).contains("href=\"" + uri + "\"");
		assertThat(message.getValue().type()).isEqualTo(EmailDeliveryType.EMAIL_VERIFY);
		assertThat(message.getValue().subject()).doesNotContain(raw);
		assertThat(message.getValue().toString()).doesNotContain(raw);
	}

	private URI link(EmailMessage message) {
		return message.textBody().lines().filter(line -> line.startsWith("https://")).map(URI::create).findFirst().orElseThrow();
	}

	private EmailVerificationService service(String base) {
		EmailTemplates templates = new EmailTemplates(new MailProperties(true, "logging", "synthetic@example.test",
			"", base, "ap-northeast-2"));
		return new EmailVerificationService(users, tokens, limits, mail, templates, Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
