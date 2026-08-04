package io.edupilot.report.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.edupilot.report.ReportSourceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReportCriterionRequest(
	@NotBlank @Size(max = 50) String criterionKey,
	@NotBlank @Size(max = 100) String name,
	@Size(max = 500) String description,
	@NotNull Map<String, Object> rubric,
	@NotEmpty List<ReportSourceType> allowedSources,
	@Min(1) int minEvidence,
	@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal weight
) {
}
