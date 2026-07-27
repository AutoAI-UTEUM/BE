package io.edupilot.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class LogbackProfileContractTest {

	private static final Logger log = LoggerFactory.getLogger(
		LogbackProfileContractTest.class
	);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void usesOneEnvironmentValueForMultipleActiveProfiles(
		CapturedOutput output
	) throws Exception {
		runProbe("profile-probe-local-test", "local", "test");
		String localLine = lineContaining(
			output.getOut(),
			"profile-probe-local-test"
		);
		assertThat(localLine)
			.contains("[environment:local]")
			.contains("[traceId:profile-contract-trace]")
			.contains("probe=\"profile-probe-local-test\"");

		runProbe("profile-probe-dev-test", "dev", "test");
		JsonNode devLog = objectMapper.readTree(lineContaining(
			output.getOut(),
			"\"message\":\"profile-probe-dev-test\""
		));
		assertJsonContract(devLog, "dev", "profile-probe-dev-test");

		runProbe("profile-probe-prod-dev", "prod", "dev");
		JsonNode prodLog = objectMapper.readTree(lineContaining(
			output.getOut(),
			"\"message\":\"profile-probe-prod-dev\""
		));
		assertJsonContract(prodLog, "prod", "profile-probe-prod-dev");
	}

	private void runProbe(String marker, String... profiles) {
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		try (ConfigurableApplicationContext ignored =
			new SpringApplicationBuilder(LoggingProbeApplication.class)
				.profiles(profiles)
				.properties(
					"spring.main.banner-mode=off",
					"spring.main.web-application-type=none",
					"logging.level.root=INFO"
				)
				.run()) {
			MDC.put("traceId", "profile-contract-trace");
			try {
				log.atInfo()
					.addKeyValue("probe", marker)
					.log(marker);
			} finally {
				MDC.remove("traceId");
			}
		}
	}

	private void assertJsonContract(
		JsonNode logLine,
		String environment,
		String marker
	) {
		assertThat(logLine.get("timestamp").asText()).isNotBlank();
		assertThat(logLine.get("level").asText()).isEqualTo("INFO");
		assertThat(logLine.get("service").asText())
			.isEqualTo("main-service");
		assertThat(logLine.get("environment").asText())
			.isEqualTo(environment);
		assertThat(logLine.get("traceId").asText())
			.isEqualTo("profile-contract-trace");
		assertThat(logLine.get("message").asText()).isEqualTo(marker);
		assertThat(logLine.get("probe").asText()).isEqualTo(marker);
	}

	private String lineContaining(String output, String marker) {
		return output.lines()
			.filter(line -> line.contains(marker))
			.reduce((first, second) -> second)
			.orElseThrow();
	}

	@SpringBootConfiguration
	static class LoggingProbeApplication {
	}
}
