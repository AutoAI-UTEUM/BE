package io.edupilot.exam;

import io.edupilot.exam.dto.ExamAttemptDraftResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class ExamDraftVersionConflictException extends BusinessException {

	private final ExamAttemptDraftResponse latestDraft;

	public ExamDraftVersionConflictException(ExamAttemptDraftResponse latestDraft) {
		super(ErrorCode.DRAFT_VERSION_CONFLICT);
		this.latestDraft = latestDraft;
	}

	public ExamAttemptDraftResponse latestDraft() {
		return latestDraft;
	}
}
