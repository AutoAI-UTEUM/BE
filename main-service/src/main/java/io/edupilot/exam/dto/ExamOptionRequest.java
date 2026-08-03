package io.edupilot.exam.dto;

import jakarta.validation.constraints.NotBlank;

public record ExamOptionRequest(
	@NotBlank String optionId,
	@NotBlank String text
) {
}
