package io.edupilot.exam.dto;

import java.util.List;

import io.edupilot.ai.dto.ExamDraftResponse;

public record ExamDraftQuestionsResponse(
	String schemaVersion,
	Long examId,
	List<ExamDraftResponse.Question> questions,
	ExamDraftResponse.Usage usage,
	boolean truncated
) {

	public static ExamDraftQuestionsResponse from(
		ExamDraftResponse response,
		boolean truncated
	) {
		return new ExamDraftQuestionsResponse(
			response.schemaVersion(),
			response.examId(),
			response.questions(),
			response.usage(),
			truncated
		);
	}
}
