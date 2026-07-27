package io.edupilot.ai.dto;

import java.util.List;
import java.util.Map;

public record TurnResponse(
	String schemaVersion,
	String turnId,
	String turnGoal,
	List<Map<String, Object>> actionsExecuted,
	List<Map<String, Object>> messages,
	Map<String, Object> statePatch,
	List<Map<String, Object>> uiActions,
	List<Map<String, Object>> memoryCandidates,
	Map<String, Object> memoryWrite,
	AiUsage usage
) {
}
