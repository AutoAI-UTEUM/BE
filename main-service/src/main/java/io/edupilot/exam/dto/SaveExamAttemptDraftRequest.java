package io.edupilot.exam.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SaveExamAttemptDraftRequest(
	@Min(0) Integer version,
	@NotNull List<@Valid ExamAnswerRequest> answers
) {
}
