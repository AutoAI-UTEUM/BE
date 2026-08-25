package io.edupilot.exam.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitExamRequest(
	@NotBlank String requestId,
	@NotNull List<@Valid ExamAnswerRequest> answers
) {
}
