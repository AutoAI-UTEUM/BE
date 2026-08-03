package io.edupilot.ai.dto;

import java.util.List;

public record ReportGenerateRequest(
	String schemaVersion,
	String reportId,
	String generationId,
	Scope scope,
	List<Metric> metrics,
	DataQuality dataQuality,
	List<Criterion> criteria,
	List<Evidence> evidence,
	PreviousReport previousReport
) {
	public enum MetricWindow {
		CUMULATIVE,
		RECENT
	}

	public enum EvidenceSourceType {
		QUIZ,
		QA,
		DIAGNOSIS,
		REPAIR,
		MEMORY,
		EXAM
	}

	public record Scope(
		String label,
		String periodStart,
		String periodEnd
	) {
	}

	public record Metric(
		String key,
		String label,
		String value,
		MetricWindow window
	) {
	}

	public record DataQuality(
		String policyVersion,
		List<EvidenceSourceType> availableSources,
		List<EvidenceSourceType> missingSources,
		List<CriterionEligibility> criterionEligibility
	) {
	}

	public record CriterionEligibility(
		String criterionKey,
		boolean eligible,
		String reason
	) {
	}

	public record Criterion(
		String key,
		String name,
		String description,
		String rubric,
		List<EvidenceSourceType> allowedSourceTypes,
		int minimumEvidence,
		int version
	) {
	}

	public record Evidence(
		String evidenceId,
		EvidenceSourceType sourceType,
		String occurredAt,
		String label,
		String fact
	) {
	}

	public record PreviousReport(
		int version,
		List<PreviousCriterionResult> criterionResults
	) {
	}

	public record PreviousCriterionResult(
		String criterionKey,
		ReportGenerateResponse.CriterionStatus status,
		Integer score
	) {
	}
}
