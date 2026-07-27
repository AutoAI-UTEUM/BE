package io.edupilot.memory;

import java.util.List;

public record MemoryWrite(
	List<String> strengths,
	List<String> weaknesses,
	List<String> misconceptions,
	List<String> explanationPreferences,
	List<String> preferredQuizTypes,
	String targetDifficulty,
	List<String> nextCoachingGoals,
	String memoryDigest,
	List<Long> candidateIds
) {
}
