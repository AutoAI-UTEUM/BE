package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReportGenerateResponse(
	String schemaVersion,
	String reportId,
	List<CriterionResult> criterionResults,
	Summary summary,
	List<Warning> warnings,
	AiUsage usage,
	BigDecimal overallScore,
	String overallStage
) {
	public enum CriterionStatus {
		ASSESSED,
		INSUFFICIENT_DATA
	}

	public enum WarningType {
		CONFLICTING_EVIDENCE,
		SPARSE_EVIDENCE,
		OUT_OF_SCOPE_INPUT
	}

	public record CriterionResult(
		String criterionKey,
		CriterionStatus status,
		Integer score,
		String narrative,
		List<String> evidenceIds
	) {
	}

	public record EvidencedStatement(
		String content,
		List<String> evidenceIds
	) {
	}

	public record Summary(
		String overview,
		List<EvidencedStatement> strengths,
		List<EvidencedStatement> improvements,
		List<EvidencedStatement> misconceptionCandidates,
		List<EvidencedStatement> recommendedActions
	) {
	}

	public record Warning(
		WarningType type,
		String message,
		List<String> evidenceIds
	) {
	}
}
