package io.edupilot.deletion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Validated
@ConfigurationProperties("edupilot.deletion")
public record DeletionProperties(
	@DefaultValue("false") boolean enabled,
	String policyVersion,
	@Min(0) Integer originalRetentionDays,
	@Min(0) Integer renderRetentionDays,
	@Min(0) Integer externalRetentionDays,
	@Min(0) Integer avatarRetentionDays,
	@DefaultValue("2m") @NotNull Duration leaseDuration,
	@DefaultValue("1m") @NotNull Duration retryDelay,
	@DefaultValue("5") @Min(1) @Max(100) int maxAttempts,
	@DefaultValue("25") @Min(1) @Max(100) int batchSize
) {
	public Integer days(DeletionKind kind) {
		return switch (kind) {
			case ORIGINAL_PDF -> originalRetentionDays;
			case RENDERED_PAGES -> renderRetentionDays;
			case EXTERNAL_AI -> externalRetentionDays;
			case AVATAR -> avatarRetentionDays;
			case ACCOUNT -> null;
		};
	}
	public boolean permits(DeletionKind kind) {
		return enabled && policyVersion != null && !policyVersion.isBlank() && days(kind) != null;
	}
	@AssertTrue(message = "Deletion lease and retry durations must be positive")
	public boolean isDurationsValid() {
		return leaseDuration != null && !leaseDuration.isNegative() && !leaseDuration.isZero()
			&& retryDelay != null && !retryDelay.isNegative() && !retryDelay.isZero();
	}
	@AssertTrue(message = "Enabled physical deletion needs an explicitly selected policy version")
	public boolean isPolicyVersionValid() {
		return !enabled || (policyVersion != null && !policyVersion.isBlank() && policyVersion.length() <= 100);
	}
}
