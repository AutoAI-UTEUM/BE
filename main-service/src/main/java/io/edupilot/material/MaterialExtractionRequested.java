package io.edupilot.material;

public record MaterialExtractionRequested(
	Long materialId,
	String traceId
) {
}
