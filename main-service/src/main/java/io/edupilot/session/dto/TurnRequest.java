package io.edupilot.session.dto;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TurnRequest(
	@NotBlank @Size(max = 255) String requestId,
	@NotBlank String eventType,
	@NotNull JsonNode payload
) {
}
