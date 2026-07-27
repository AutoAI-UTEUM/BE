package io.edupilot.memory;

public record MemoryEvidenceRef(
	String sourceType,
	Long sourceId,
	Long sessionId
) {
}
