package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.report.ReportSourceType;

public record CriteriaSuggestResponse(
	String schemaVersion,
	List<Criterion> criteria,
	List<Warning> warnings,
	AiUsage usage
) {
	public record Criterion(
		String key,
		String name,
		String description,
		String rubric,
		List<ReportSourceType> allowedSources,
		BigDecimal weight,
		int minimumEvidence
	) {
	}

	public record Warning(String type, String message) {
	}
}
