package io.edupilot.material;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.material.xai-files.backfill")
public record MaterialXaiFileBackfillProperties(
	boolean enabled,
	@Min(1) @Max(10) int batchSize,
	@NotNull Duration retryBackoff
) {
}
