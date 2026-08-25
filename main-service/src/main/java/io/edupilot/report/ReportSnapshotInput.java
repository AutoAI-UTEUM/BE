package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReportSnapshotInput(
	List<ReportCriterionDefinition> catalog,
	List<SourceRecord> sources,
	ProgressRecord progress,
	Instant sourceDataAsOf
) {
	public ReportSnapshotInput {
		catalog = List.copyOf(catalog);
		sources = List.copyOf(sources);
	}

	public record SourceRecord(
		ReportSourceType sourceType,
		String sourceRef,
		Instant occurredAt,
		String publicLabel,
		Map<String, Object> minimalFact,
		BigDecimal score,
		BigDecimal maxScore,
		BigDecimal normalizedScore
	) {
		public SourceRecord {
			minimalFact = Map.copyOf(minimalFact);
		}
	}

	public record ProgressRecord(
		long explainedPages,
		long totalPages,
		int progressRate,
		boolean progressDataAvailable
	) {
	}
}
