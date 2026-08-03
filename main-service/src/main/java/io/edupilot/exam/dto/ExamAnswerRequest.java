package io.edupilot.exam.dto;

import jakarta.validation.constraints.NotBlank;

public record ExamAnswerRequest(
	@NotBlank String questionId,
	String answer
) {
}
