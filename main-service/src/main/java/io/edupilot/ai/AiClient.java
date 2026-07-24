package io.edupilot.ai;

import io.edupilot.ai.dto.AiHealthResponse;
import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.TurnResponse;

public interface AiClient {

	AiHealthResponse health();

	TurnResponse executeTurn(TurnRequest request);
}
