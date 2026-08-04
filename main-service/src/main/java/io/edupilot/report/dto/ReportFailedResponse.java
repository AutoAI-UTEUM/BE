package io.edupilot.report.dto;

import java.util.Map;

import io.edupilot.report.ReportGenerationStatus;

public record ReportFailedResponse(
	String reportId,
	ReportGenerationStatus status,
	String failureCode,
	Fallback fallback
) {
	public record Fallback(
		Map<String, Object> metrics,
		Map<String, Object> dataQuality
	) {
	}
}
