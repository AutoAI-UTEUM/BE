package io.edupilot.deletion;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GeneralFileRetentionPropertiesTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Binding.class);

	@Test
	void approvedThirtyDaysBindsWithoutEnablingAnyPhysicalDeletionKind() {
		context.run(app -> {
			assertThat(app).hasNotFailed();
			var general = app.getBean(GeneralFileRetentionProperties.class);
			assertThat(general.retentionDays()).isEqualTo(30);
			assertThat(general.deadline(Instant.parse("2026-10-04T00:00:00Z")))
				.isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
			var physical = app.getBean(DeletionProperties.class);
			assertThat(physical.enabled()).isFalse();
			for (var kind : DeletionKind.values()) { assertThat(physical.permits(kind)).isFalse(); }
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 29, 31})
	void configurationCannotSilentlyChangeTheApprovedThirtyDayPeriod(int days) {
		context.withPropertyValues("edupilot.deletion.general-files.retention-days=" + days)
			.run(app -> assertThat(app).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({GeneralFileRetentionProperties.class, DeletionProperties.class})
	static class Binding {}
}
