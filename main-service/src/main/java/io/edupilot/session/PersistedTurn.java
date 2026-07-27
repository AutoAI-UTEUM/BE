package io.edupilot.session;

import io.edupilot.memory.MemoryWrite;
import io.edupilot.session.dto.TurnResponse;

public record PersistedTurn(
	TurnResponse response,
	MemoryWrite memoryWrite,
	Long materialId
) {
}
