package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.report")
public record ReportProperties(
	@NotNull Duration recentWindow,
	@Min(1) int evidenceLimit,
	@NotNull @DecimalMin("0.0") BigDecimal trendThreshold,
	@NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal stageExcellent,
	@NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal stageGood,
	@NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal stageFair,
	@NotBlank String policyVersion
) {
	@AssertTrue(message = "report stage boundaries must be excellent > good > fair")
	public boolean isStageOrderValid() {
		return stageExcellent.compareTo(stageGood) > 0
			&& stageGood.compareTo(stageFair) > 0;
	}
}
