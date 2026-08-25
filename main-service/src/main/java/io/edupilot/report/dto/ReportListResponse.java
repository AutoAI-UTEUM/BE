package io.edupilot.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.edupilot.report.ReportGenerationStatus;

public record ReportListResponse(
	List<CompletedReportItem> items,
	ActiveGeneration activeGeneration
) {
	public record CompletedReportItem(
		String reportId,
		int version,
		BigDecimal overallScore,
		String overallStage,
		Instant createdAt
	) {
	}

	public record ActiveGeneration(
		String reportId,
		ReportGenerationStatus status,
		int pollAfterSeconds
	) {
	}
}
