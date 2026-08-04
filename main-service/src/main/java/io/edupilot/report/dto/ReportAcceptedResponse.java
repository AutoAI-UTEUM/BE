package io.edupilot.report.dto;

import io.edupilot.report.ReportGenerationStatus;

public record ReportAcceptedResponse(
	String reportId,
	ReportGenerationStatus status,
	int pollAfterSeconds
) {
}
