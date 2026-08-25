package io.edupilot.ai.dto;

import java.util.List;
import java.util.Map;

public record TurnResponse(
	String schemaVersion,
	String turnId,
	String turnGoal,
	List<ActionExecuted> actionsExecuted,
	List<Map<String, Object>> messages,
	Map<String, Object> statePatch,
	List<Map<String, Object>> uiActions,
	QuizGeneration quiz,
	List<Map<String, Object>> memoryCandidates,
	Map<String, Object> memoryWrite,
	NoteDraft noteDraft,
	AiUsage usage
) {

	public TurnResponse(
		String schemaVersion,
		String turnId,
		String turnGoal,
		List<ActionExecuted> actionsExecuted,
		List<Map<String, Object>> messages,
		Map<String, Object> statePatch,
		List<Map<String, Object>> uiActions,
		QuizGeneration quiz,
		List<Map<String, Object>> memoryCandidates,
		Map<String, Object> memoryWrite,
		AiUsage usage
	) {
		this(
			schemaVersion,
			turnId,
			turnGoal,
			actionsExecuted,
			messages,
			statePatch,
			uiActions,
			quiz,
			memoryCandidates,
			memoryWrite,
			null,
			usage
		);
	}
}
