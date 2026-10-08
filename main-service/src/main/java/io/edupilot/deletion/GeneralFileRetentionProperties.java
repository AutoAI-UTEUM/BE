package io.edupilot.deletion;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Approved ordinary material-file retention, independent of physical deletion enablement. */
@Validated
@ConfigurationProperties("edupilot.deletion.general-files")
public record GeneralFileRetentionProperties(@DefaultValue("30") @Min(30) @Max(30) int retentionDays) {
	public Instant deadline(Instant requestedAt) {
		return requestedAt.plus(Duration.ofDays(retentionDays));
	}
}
