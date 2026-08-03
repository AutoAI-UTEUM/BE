package io.edupilot.ai;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.ai")
public record AiClientProperties(
	@NotNull URI baseUrl,
	@NotBlank String internalToken,
	@NotNull Duration connectTimeout,
	@NotNull Duration healthTimeout,
	@NotNull Duration readTimeout,
	@NotNull Duration turnReadTimeout,
	@NotNull Duration streamIdleTimeout,
	@NotNull Duration gradeReadTimeout,
	@NotNull Duration pipelineReadTimeout,
	@NotNull Duration extractReadTimeout,
	@NotNull Duration reportReadTimeout,
	@NotBlank String healthPath
) {
}
