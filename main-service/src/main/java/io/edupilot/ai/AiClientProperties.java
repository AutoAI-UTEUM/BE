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
	public AiClientProperties(
		URI baseUrl,
		String internalToken,
		Duration connectTimeout,
		Duration healthTimeout,
		Duration readTimeout,
		Duration turnReadTimeout,
		Duration streamIdleTimeout,
		Duration gradeReadTimeout,
		Duration pipelineReadTimeout,
		Duration extractReadTimeout,
		String healthPath
	) {
		this(
			baseUrl,
			internalToken,
			connectTimeout,
			healthTimeout,
			readTimeout,
			turnReadTimeout,
			streamIdleTimeout,
			gradeReadTimeout,
			pipelineReadTimeout,
			extractReadTimeout,
			Duration.ofSeconds(180),
			healthPath
		);
	}
}
