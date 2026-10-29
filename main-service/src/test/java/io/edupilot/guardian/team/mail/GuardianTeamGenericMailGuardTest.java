package io.edupilot.guardian.team.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailOutboxWorker;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.MailProperties;

@ExtendWith(MockitoExtension.class)
class GuardianTeamGenericMailGuardTest {
	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	@Mock private EmailDeliveryStore history;
	@Mock private EmailOutboxStore outbox;
	@Mock private EmailOutboxWorker worker;

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void genericSendRejectsGuardianPurposeBeforeCreatingEvenDisabledMetadata(boolean enabled) {
		EmailService service = new EmailService(history, outbox, worker,
			new MailProperties(enabled, "logging", "sender@example.test", "", "https://portal.example.test", ""),
			Clock.fixed(NOW, ZoneOffset.UTC));
		assertThatThrownBy(() -> service.sendAsync(guardianMessage()))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(history, outbox, worker);
	}

	@Test
	void explicitExpiryCannotBypassTheUnsupportedGuardianPurpose() {
		EmailService service = new EmailService(history, outbox, worker,
			new MailProperties(true, "logging", "sender@example.test", "", "https://portal.example.test", ""),
			Clock.fixed(NOW, ZoneOffset.UTC));
		assertThatThrownBy(() -> service.sendAsync(guardianMessage(), NOW.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(history, outbox, worker);
	}

	private EmailMessage guardianMessage() {
		return new EmailMessage("guardian@example.test", "보호자 동의 안내", "합성 보호자 안내 본문", null,
			EmailDeliveryType.GUARDIAN_TEAM_NOTICE);
	}
}
