package io.edupilot.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "edupilot.summary")
public record ConversationSummaryProperties(
	@Positive int turnInterval
) {
}
