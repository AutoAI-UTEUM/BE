package io.edupilot.material;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "edupilot.material.outline")
public record MaterialOutlineProperties(
	@Min(1) int backfillBatch
) {
}
