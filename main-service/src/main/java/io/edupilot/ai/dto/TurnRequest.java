package io.edupilot.ai.dto;

import java.util.Map;

public record TurnRequest(
	String schemaVersion,
	String turnId,
	Map<String, Object> session,
	Map<String, Object> event,
	Map<String, Object> context
) {
}
