package io.edupilot.memory.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.memory.LearnerMemory;

public record LearnerMemoryResponse(
	Long materialId,
	List<String> strengths,
	List<String> weaknesses,
	List<String> explanationPreferences,
	List<String> preferredQuizTypes,
	String memoryDigest,
	Instant updatedAt
) {
	public static LearnerMemoryResponse empty(Long materialId) {
		return new LearnerMemoryResponse(
			materialId,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			null,
			null
		);
	}

	public static LearnerMemoryResponse from(
		Long materialId,
		LearnerMemory memory
	) {
		return new LearnerMemoryResponse(
			materialId,
			memory.getStrengths(),
			memory.getWeaknesses(),
			memory.getExplanationPreferences(),
			memory.getPreferredQuizTypes(),
			memory.getMemoryDigest(),
			memory.getUpdatedAt()
		);
	}
}
