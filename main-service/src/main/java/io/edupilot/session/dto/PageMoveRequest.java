package io.edupilot.session.dto;

import jakarta.validation.constraints.NotNull;

public record PageMoveRequest(
	@NotNull Integer pageNumber
) {
}
