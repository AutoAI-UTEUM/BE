package io.edupilot.admin.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.admin.infra")
public record AdminInfraProperties(
	boolean enabled,
	@NotBlank String region,
	@Valid @NotNull Instances instances,
	@NotNull Duration metricsCacheTtl,
	@NotNull Duration costCacheTtl
) {

	public AdminInfraProperties {
		if (metricsCacheTtl != null
			&& (metricsCacheTtl.isZero() || metricsCacheTtl.isNegative())) {
			throw new IllegalArgumentException(
				"metricsCacheTtl must be positive"
			);
		}
		if (costCacheTtl != null
			&& (costCacheTtl.isZero() || costCacheTtl.isNegative())) {
			throw new IllegalArgumentException("costCacheTtl must be positive");
		}
	}

	public record Instances(
		@NotNull String prod,
		@NotNull String dev
	) {
	}
}
