package io.edupilot.session.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateSessionRequest(
	@NotNull @Positive Long materialId
) {
}
