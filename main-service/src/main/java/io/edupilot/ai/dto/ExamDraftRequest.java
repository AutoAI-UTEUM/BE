package io.edupilot.ai.dto;

import java.util.List;

import io.edupilot.exam.ExamQuestionType;

public record ExamDraftRequest(
	String schemaVersion,
	Long examId,
	List<PageContext> pageContexts,
	List<QuestionPlanItem> questionPlan
) {

	public record PageContext(
		Integer pageNumber,
		String text
	) {
	}

	public record QuestionPlanItem(
		ExamQuestionType questionType,
		Integer count
	) {
	}
}
