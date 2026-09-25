package io.edupilot.usernote.dto;

import java.time.Instant;

import io.edupilot.usernote.WrongAnswerNote;
import io.edupilot.usernote.WrongAnswerQuestionSnapshot;

public record WrongAnswerNoteResponse(
	Long id,
	String quizResultRef,
	WrongAnswerQuestionSnapshot questionSnapshot,
	String memo,
	Instant createdAt,
	Instant updatedAt
) {
	public static WrongAnswerNoteResponse from(WrongAnswerNote note) {
		return new WrongAnswerNoteResponse(
			note.getId(), note.getQuizResultRef(), note.getQuestionSnapshot(),
			note.getMemo(), note.getCreatedAt(), note.getUpdatedAt()
		);
	}
}
