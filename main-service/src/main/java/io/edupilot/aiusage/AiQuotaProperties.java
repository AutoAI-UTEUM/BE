package io.edupilot.aiusage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "edupilot.ai.quota")
public record AiQuotaProperties(
	@DefaultValue("true") boolean enabled,
	@Min(1) @DefaultValue("200") long dailyDefault,
	@Min(1) @DefaultValue("500") long dailyInstructor
) {
}
