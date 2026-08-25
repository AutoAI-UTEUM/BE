package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ReportSnapshot(
	Metrics metrics,
	DataQuality dataQuality,
	List<Evidence> evidence,
	Instant sourceDataAsOf,
	String snapshotHash
) {
	public ReportSnapshot {
		evidence = List.copyOf(evidence);
	}

	public record Metrics(
		Progress progress,
		ScoreWindow quiz,
		ScoreWindow exam,
		Questions questions,
		Activity sessions
	) {
	}

	public record Progress(
		long explainedPages,
		long totalPages,
		int progressRate,
		boolean progressDataAvailable
	) {
	}

	public record ScoreAggregate(
		long submissionCount,
		BigDecimal averageNormalizedScore
	) {
	}

	public record ScoreWindow(
		ScoreAggregate cumulative,
		ScoreAggregate recent
	) {
	}

	public record Questions(
		long cumulativeCount,
		long recentCount
	) {
	}

	public record Activity(
		long sessionCount,
		Instant lastActivityAt
	) {
	}

	public record DataQuality(
		String policyVersion,
		Set<ReportSourceType> availableSources,
		Set<ReportSourceType> missingSources,
		Map<String, Eligibility> criterionEligibility
	) {
		public DataQuality {
			availableSources = Set.copyOf(availableSources);
			missingSources = Set.copyOf(missingSources);
			criterionEligibility = Map.copyOf(criterionEligibility);
		}
	}

	public record Eligibility(
		boolean eligible,
		String reason,
		int evidenceCount,
		int minEvidence
	) {
	}

	public record Evidence(
		String evidenceId,
		ReportSourceType sourceType,
		String sourceRef,
		Instant occurredAt,
		String publicLabel,
		Map<String, Object> minimalFact
	) {
		public Evidence {
			minimalFact = Map.copyOf(minimalFact);
		}
	}
}
