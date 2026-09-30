package io.edupilot.ai.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

public record TurnRequest(
	String schemaVersion,
	String turnId,
	Map<String, Object> session,
	Map<String, Object> event,
	Map<String, Object> context,
	@JsonInclude(JsonInclude.Include.NON_NULL)
	Map<String, Object> capabilities
) {
	public TurnRequest(
		String schemaVersion,
		String turnId,
		Map<String, Object> session,
		Map<String, Object> event,
		Map<String, Object> context
	) {
		this(schemaVersion, turnId, session, event, context, null);
	}
}
