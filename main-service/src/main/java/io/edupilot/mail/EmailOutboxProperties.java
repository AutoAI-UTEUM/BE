package io.edupilot.mail;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "edupilot.mail.outbox")
public record EmailOutboxProperties(
	String encryptionKey,
	@DefaultValue("PT2M") Duration leaseDuration,
	@DefaultValue("PT30S") Duration retryDelay,
	@DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
	@DefaultValue("50") @Min(1) @Max(200) int batchSize
) {
	public EmailOutboxProperties {
		if (leaseDuration == null || leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
			|| leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
			|| retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
			|| retryDelay.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("Invalid mail outbox lease/retry duration");
		}
	}
}
