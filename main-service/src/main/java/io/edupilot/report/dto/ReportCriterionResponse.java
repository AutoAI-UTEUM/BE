package io.edupilot.report.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ReportCriterionResponse(
	Long criterionId,
	String criterionKey,
	String name,
	String description,
	Map<String, Object> rubric,
	List<String> allowedSources,
	int minEvidence,
	BigDecimal weight,
	String version,
	boolean active,
	boolean builtin
) {
}
