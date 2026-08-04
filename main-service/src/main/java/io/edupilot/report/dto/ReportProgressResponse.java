package io.edupilot.report.dto;

import io.edupilot.report.ReportGenerationStatus;

public record ReportProgressResponse(
	String reportId,
	ReportGenerationStatus status,
	int pollAfterSeconds
) {
}
