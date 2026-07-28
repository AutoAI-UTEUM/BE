package io.edupilot.assessment;

import java.util.List;

public record QuizAssessmentData(
	String schemaVersion,
	String understandingSummary,
	List<String> strengths,
	List<String> weaknesses,
	List<String> suspectedMisconceptions,
	String recommendedNextDirection,
	List<AssessmentMemoryCandidate> memoryCandidates,
	List<String> evidence
) {
	public record AssessmentMemoryCandidate(
		String type,
		String content,
		java.math.BigDecimal confidence
	) {
	}
}
