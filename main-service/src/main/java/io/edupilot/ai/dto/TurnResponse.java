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

	public boolean isDirectNote(String eventType, int contentDeltaCount) {
		return "USER_QUESTION".equals(eventType)
			&& contentDeltaCount == 0
			&& hasNoteResponseShape();
	}

	public boolean hasNoteResponseShape() {
		if (noteDraft == null
			|| statePatch == null
			|| !statePatch.isEmpty()
			|| quiz != null
			|| messages == null
			|| messages.size() != 1) {
			return false;
		}
		Map<String, Object> message = messages.getFirst();
		return message != null
			&& "SYSTEM".equals(message.get("messageType"));
	}

	public boolean hasNoteResponseSignal() {
		return noteDraft != null
			|| (messages != null && messages.stream().anyMatch(message ->
				message != null
					&& "SYSTEM".equals(message.get("messageType"))
			));
	}

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
