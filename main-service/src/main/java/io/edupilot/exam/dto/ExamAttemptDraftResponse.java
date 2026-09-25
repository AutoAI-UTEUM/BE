package io.edupilot.exam.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.exam.ExamAttemptDraft;

public record ExamAttemptDraftResponse(
	int version,
	List<ExamAnswerRequest> answers,
	Instant savedAt
) {

	public static ExamAttemptDraftResponse from(ExamAttemptDraft draft) {
		return new ExamAttemptDraftResponse(
			draft.getVersion(), draft.getAnswers(), draft.getUpdatedAt()
		);
	}
}
