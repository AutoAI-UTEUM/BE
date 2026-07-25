package io.edupilot.ai;

import io.edupilot.ai.dto.AiHealthResponse;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.TurnResponse;
import org.springframework.core.io.Resource;

public interface AiClient {

	AiHealthResponse health();

	TurnResponse executeTurn(TurnRequest request);

	ExtractResponse extract(Resource pdfResource);

	GradeResponse grade(GradeRequest request);
}
