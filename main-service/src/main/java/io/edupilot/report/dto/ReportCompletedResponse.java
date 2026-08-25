package io.edupilot.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.edupilot.report.ReportCriterionStatus;
import io.edupilot.report.ReportGenerationStatus;
import io.edupilot.report.ReportTrend;

public record ReportCompletedResponse(
	String reportId,
	ReportGenerationStatus status,
	int version,
	Integer previousVersion,
	BigDecimal overallScore,
	String overallStage,
	Object summary,
	List<CriterionResult> criteria,
	List<Evidence> evidence,
	Instant createdAt
) {
	public record CriterionResult(
		String criterionKey,
		int criterionVersion,
		BigDecimal score,
		ReportTrend trend,
		ReportCriterionStatus status,
		String narrative,
		List<String> evidenceIds
	) {
	}

	public record Evidence(
		String evidenceId,
		String sourceType,
		String publicLabel,
		Instant occurredAt,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<Metric> metrics
	) {
		public Evidence(
			String evidenceId,
			String sourceType,
			String publicLabel,
			Instant occurredAt
		) {
			this(evidenceId, sourceType, publicLabel, occurredAt, List.of());
		}
	}

	public record Metric(
		String label,
		String value
	) {
	}
}
