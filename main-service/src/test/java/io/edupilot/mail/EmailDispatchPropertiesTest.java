package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EmailDispatchPropertiesTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@Test void omittedModeKeepsNormalDispatchAndEmptyEnvironmentPlaceholdersBind() {
		context.withPropertyValues("edupilot.mail.outbox.dispatch.delivery-ids=",
			"edupilot.mail.outbox.dispatch.recipient=").run(app -> {
				assertThat(app).hasNotFailed();
				EmailDispatchProperties properties = app.getBean(EmailDispatchProperties.class);
				assertThat(properties.mode()).isEqualTo(EmailDispatchProperties.Mode.NORMAL);
				assertThat(properties.blocks(999L)).isFalse();
			});
	}

	@Test void isolatedTrialBindsExactIdsWithoutPrintingItsRecipient() {
		context.withPropertyValues("edupilot.mail.outbox.dispatch.mode=ISOLATED_TRIAL",
			"edupilot.mail.outbox.dispatch.delivery-ids=17,23",
			"edupilot.mail.outbox.dispatch.recipient=trial@example.test").run(app -> {
				assertThat(app).hasNotFailed();
				EmailDispatchProperties properties = app.getBean(EmailDispatchProperties.class);
				assertThat(properties.blocks(17L)).isFalse();
				assertThat(properties.blocks(24L)).isTrue();
				assertThat(properties.toString()).contains("ISOLATED_TRIAL", "approvedDeliveries=2")
					.doesNotContain("trial@example.test");
			});
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "0", "-1", "1,2,3,4", "not-an-id"})
	void missingInvalidOrMoreThanThreeIdsFailStartup(String ids) {
		context.withPropertyValues("edupilot.mail.outbox.dispatch.mode=ISOLATED_TRIAL",
			"edupilot.mail.outbox.dispatch.delivery-ids=" + ids,
			"edupilot.mail.outbox.dispatch.recipient=trial@example.test")
			.run(app -> assertThat(app).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "bad", "one@example.test,two@example.test", "one@example.test\nBcc:two@example.test"})
	void missingOrMultipleRecipientsFailStartup(String recipient) {
		context.withPropertyValues("edupilot.mail.outbox.dispatch.mode=ISOLATED_TRIAL",
			"edupilot.mail.outbox.dispatch.delivery-ids=1",
			"edupilot.mail.outbox.dispatch.recipient=" + recipient)
			.run(app -> assertThat(app).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"NORMAL", "PAUSED", "MISSPELLED"})
	void approvalsCannotSilentlyFallBackToUnrestrictedMode(String mode) {
		context.withPropertyValues("edupilot.mail.outbox.dispatch.mode=" + mode,
			"edupilot.mail.outbox.dispatch.delivery-ids=1",
			"edupilot.mail.outbox.dispatch.recipient=trial@example.test")
			.run(app -> assertThat(app).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(EmailDispatchProperties.class)
	static class PropertiesConfig { }
}
