package io.edupilot.quiz;

import io.edupilot.quiz.dto.QuizDraftResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class QuizDraftVersionConflictException extends BusinessException {

	private final QuizDraftResponse latestDraft;

	public QuizDraftVersionConflictException(QuizDraftResponse latestDraft) {
		super(ErrorCode.DRAFT_VERSION_CONFLICT);
		this.latestDraft = latestDraft;
	}

	public QuizDraftResponse latestDraft() {
		return latestDraft;
	}
}
