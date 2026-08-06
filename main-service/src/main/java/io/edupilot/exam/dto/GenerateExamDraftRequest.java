package io.edupilot.exam.dto;

import java.util.List;

import io.edupilot.exam.ExamQuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GenerateExamDraftRequest(
	@Min(1) Integer weekNumber,
	@Size(min = 1) List<@NotNull Long> materialIds,
	@NotNull @Size(min = 1, max = 4) List<@Valid QuestionPlanItem> questionPlan
) {

	public record QuestionPlanItem(
		@NotNull ExamQuestionType questionType,
		@NotNull @Min(1) Integer count
	) {
	}
}
