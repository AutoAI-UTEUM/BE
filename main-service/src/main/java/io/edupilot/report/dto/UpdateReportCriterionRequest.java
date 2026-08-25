package io.edupilot.report.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.edupilot.report.ReportSourceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateReportCriterionRequest(
	@Size(min = 1, max = 100) String name,
	@Size(max = 500) String description,
	Map<String, Object> rubric,
	@Size(min = 1) List<ReportSourceType> allowedSources,
	@Min(1) Integer minEvidence,
	@DecimalMin(value = "0", inclusive = false) BigDecimal weight,
	Boolean active
) {
	public boolean hasContentChanges() {
		return name != null || description != null || rubric != null
			|| allowedSources != null || minEvidence != null || weight != null;
	}

	public boolean hasAnyChange() {
		return hasContentChanges() || active != null;
	}
}
