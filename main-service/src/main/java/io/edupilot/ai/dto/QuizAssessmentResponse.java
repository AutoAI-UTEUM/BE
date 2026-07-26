package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuizAssessmentResponse(
	String schemaVersion,
	String understandingSummary,
	List<String> strengths,
	List<String> weaknesses,
	List<String> suspectedMisconceptions,
	String recommendedNextDirection,
	List<MemoryCandidate> memoryCandidates,
	List<String> evidence,
	AiUsage usage
) {
	public record MemoryCandidate(
		String type,
		String content,
		BigDecimal confidence
	) {
	}
}
