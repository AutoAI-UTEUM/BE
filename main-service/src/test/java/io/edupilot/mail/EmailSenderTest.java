package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailSenderTest {

	@Mock private SesV2Client client;

	@Test
	void sesUsesInstanceRoleClientRequestAndReturnsProviderId() {
		when(client.sendEmail(any(SendEmailRequest.class)))
			.thenReturn(SendEmailResponse.builder().messageId("ses-id").build());
		SesEmailSender sender = new SesEmailSender(client, new MailProperties(
			true, "ses", "no-reply@uteum.com", "reply@uteum.com",
			"https://www.uteum.com", "ap-northeast-2"
		));

		assertThat(sender.send(message()).providerMessageId()).isEqualTo("ses-id");
		ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(client).sendEmail(request.capture());
		assertThat(request.getValue().fromEmailAddress()).isEqualTo("no-reply@uteum.com");
		assertThat(request.getValue().destination().toAddresses()).containsExactly("person@example.com");
		assertThat(request.getValue().replyToAddresses()).containsExactly("reply@uteum.com");
		assertThat(request.getValue().content().simple().body().text().data())
			.isEqualTo("secret body");
	}

	@Test
	void loggingProviderSuppressesContentAndTokenLinksInEveryNonprodProfile(CapturedOutput output) {
		for (String profile : java.util.List.of("local", "dev", "test")) {
			MockEnvironment environment = new MockEnvironment();
			environment.setActiveProfiles(profile);
			LoggingEmailSender sender = new LoggingEmailSender(environment);
			assertThat(sender.send(new EmailMessage("person@example.com", "subject secret-token",
				"https://dev.uteum.com/reset?token=secret-token",
				"<a href='https://dev.uteum.com/reset?token=html-secret'>Reset</a>",
				EmailDeliveryType.PASSWORD_RESET)).providerMessageId()).startsWith("logging-");
		}
		assertThat(output).contains("Mail delivery simulated; content suppressed")
			.doesNotContain("person@example.com", "secret-token", "html-secret", "textBody", "htmlBody");
	}

	@Test
	void productionLoggingProviderFailsContextStartupForExplicitAndMissingProvider() {
		ApplicationContextRunner runner = productionLoggingContext();
		runner.withPropertyValues("edupilot.mail.provider=logging")
			.run(context -> assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("edupilot.mail.provider=logging is not allowed in prod; set provider=ses"));
		runner.run(context -> assertThat(context.getStartupFailure())
			.hasRootCauseInstanceOf(IllegalStateException.class)
			.hasRootCauseMessage("edupilot.mail.provider=logging is not allowed in prod; set provider=ses"));
	}

	@Test
	void productionLoggingProviderAllowsOptInAndSuppressesBody(CapturedOutput output) {
		productionLoggingContext().withPropertyValues(
			"edupilot.mail.provider=logging",
			"edupilot.mail.allow-logging-in-prod=true"
		).run(context -> {
			assertThat(context.getStartupFailure()).isNull();
			assertThat(context.getBean(LoggingEmailSender.class).send(message()).providerMessageId())
				.startsWith("logging-");
		});
		assertThat(output).contains("Logging mail provider selected in prod")
			.doesNotContain("secret body");
	}

	@Test
	void onlyDefiniteThrottleRejectionsAreMarkedRetryable() {
		SesEmailSender sender = new SesEmailSender(client, new MailProperties(
			true, "ses", "no-reply@uteum.com", "", "https://dev.uteum.com", "ap-northeast-2"));
		when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(429).build());
		assertThatThrownBy(() -> sender.send(message())).isInstanceOfSatisfying(EmailSendRejection.class,
			error -> assertThat(error.retryable()).isTrue());
		when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(400).build());
		assertThatThrownBy(() -> sender.send(message())).isInstanceOfSatisfying(EmailSendRejection.class,
			error -> assertThat(error.retryable()).isFalse());
		when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(500).build());
		assertThatThrownBy(() -> sender.send(message())).isInstanceOf(SesV2Exception.class);
	}

	private ApplicationContextRunner productionLoggingContext() {
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
			.withUserConfiguration(LoggingEmailSender.class);
	}

	private EmailMessage message() {
		return new EmailMessage(
			"person@example.com", "Test subject", "secret body", null,
			EmailDeliveryType.TEST
		);
	}
}
