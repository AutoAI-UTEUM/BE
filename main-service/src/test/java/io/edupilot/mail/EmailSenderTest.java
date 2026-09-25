package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
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
	void loggingProviderShowsBodyOnlyOutsideProd(CapturedOutput output) {
		LoggingEmailSender sender = new LoggingEmailSender(
			new MockEnvironment().withProperty("spring.profiles.active", "dev")
		);
		assertThat(sender.send(message()).providerMessageId()).startsWith("logging-");
		assertThat(output).contains("Test subject", "secret body");
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
