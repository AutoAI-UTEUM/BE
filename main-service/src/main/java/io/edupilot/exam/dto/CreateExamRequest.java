package io.edupilot.exam.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateExamRequest(
	@NotBlank @Size(max = 200) String title,
	@Size(max = 500) String description,
	@Min(1) Integer weekNumber,
	Boolean allowRetake,
	List<@Valid ExamQuestionRequest> questions
) {
}
