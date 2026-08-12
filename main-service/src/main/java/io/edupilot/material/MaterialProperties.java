package io.edupilot.material;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.material")
public record MaterialProperties(
	@Min(1) long uploadMaxMb,
	@Min(1) int maxPages,
	@NotNull Duration extractionStuckThreshold
) {
	public long uploadMaxBytes() {
		return Math.multiplyExact(uploadMaxMb, 1024L * 1024L);
	}
}
