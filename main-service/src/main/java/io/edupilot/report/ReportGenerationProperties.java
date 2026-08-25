package io.edupilot.report;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.report.generation")
public record ReportGenerationProperties(
	@NotNull Duration leaseDuration,
	@NotNull Duration cutoff,
	@Min(1) int pollAfterSeconds,
	@Valid @NotNull Executor executor
) {
	public record Executor(
		@Min(1) int coreSize,
		@Min(1) int maxSize,
		@Min(0) int queueCapacity
	) {
	}
}
