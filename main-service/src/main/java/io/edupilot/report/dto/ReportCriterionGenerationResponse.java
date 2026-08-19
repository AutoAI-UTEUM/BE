package io.edupilot.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportCriterionGenerationResponse(
	String status,
	Integer registeredCount,
	String message
) {
}
