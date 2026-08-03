package io.edupilot.exam;

import java.math.BigDecimal;
import java.util.List;

import io.edupilot.ai.dto.GradeRequest;

public record PreparedExamAiGrading(
	Long submissionId,
	Long examId,
	List<Group> groups
) {
	public record Group(
		ExamQuestionType questionType,
		List<Item> items
	) {
	}

	public record Item(
		String questionId,
		String question,
		String modelAnswer,
		List<GradeRequest.Rubric> rubric,
		BigDecimal maxScore,
		String studentAnswer
	) {
	}
}
