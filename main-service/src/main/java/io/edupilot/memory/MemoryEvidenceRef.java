package io.edupilot.memory;

public record MemoryEvidenceRef(
	String sourceType,
	Long sourceId,
	Long sessionId,
	String reference
) {
	public MemoryEvidenceRef(
		String sourceType,
		Long sourceId,
		Long sessionId
	) {
		this(sourceType, sourceId, sessionId, null);
	}

	public String identity() {
		if (reference != null && !reference.isBlank()) {
			return sourceType
				+ ":"
				+ reference.trim()
				+ ":"
				+ sessionId;
		}
		return sourceType + ":" + sourceId + ":" + sessionId;
	}
}
