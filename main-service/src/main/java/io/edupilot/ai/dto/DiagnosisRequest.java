package io.edupilot.ai.dto;

import java.util.List;

public record DiagnosisRequest(
	String schemaVersion,
	QuizAssessmentResponse quizAssessment,
	QuizAssessmentRequest.QuizResult quizResult,
	List<WrongItem> wrongItems,
	QuizAssessmentRequest.PageContext pageContext,
	String learnerMemoryDigest
) {
	public record WrongItem(
		String questionId,
		String question,
		String studentAnswer,
		String modelAnswer,
		String feedback
	) {
	}
}
