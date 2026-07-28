package io.edupilot.ai.dto;

public record Adjustment(
	String field,
	Object from,
	Object to,
	String reason
) {
}
