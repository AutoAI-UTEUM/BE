package io.edupilot.session;

import java.util.List;

import io.edupilot.memory.MemoryWrite;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;

public record PersistedTurn(
	String turnId,
	Long sessionId,
	List<MessageResponse> messages,
	List<UiAction> uiActions,
	TurnStateResponse state,
	MemoryWrite memoryWrite,
	Long materialId
) {

	public TurnResponse response() {
		return new TurnResponse(
			turnId,
			sessionId,
			messages,
			uiActions,
			state
		);
	}
}
