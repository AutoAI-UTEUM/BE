package io.edupilot.report;

import java.math.BigDecimal;
import java.util.Set;

public record ReportCriterionDefinition(
	String key,
	String name,
	String rubricSummary,
	Set<ReportSourceType> allowedSources,
	int minEvidence,
	BigDecimal weight,
	String version
) {
	public ReportCriterionDefinition {
		allowedSources = Set.copyOf(allowedSources);
	}
}
