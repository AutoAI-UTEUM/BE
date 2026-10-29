package io.edupilot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class TurnRequestCapabilitiesTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void omitsCapabilitiesWhenDisabledAndWritesBooleanValuesWhenEnabled() {
		TurnRequest disabled = new TurnRequest(
			"1.0", "turn-1", Map.of(), Map.of(), Map.of()
		);
		TurnRequest enabled = new TurnRequest(
			"1.0", "turn-1", Map.of(), Map.of(), Map.of(),
			Map.of("qaQuizProposal", true, "quizQuestionStream", false)
		);

		assertThat(objectMapper.writeValueAsString(disabled))
			.doesNotContain("capabilities");
		assertThat(objectMapper.writeValueAsString(enabled))
			.contains("\"qaQuizProposal\":true")
			.contains("\"quizQuestionStream\":false");
	}
}
