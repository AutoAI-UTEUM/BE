package io.edupilot.material;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "edupilot.material")
public record MaterialProperties(
	@Min(1) long uploadMaxMb,
	@Min(1) int maxPages
) {
	public long uploadMaxBytes() {
		return Math.multiplyExact(uploadMaxMb, 1024L * 1024L);
	}
}
