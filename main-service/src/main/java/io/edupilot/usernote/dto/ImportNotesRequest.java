package io.edupilot.usernote.dto;

import java.util.List;

public record ImportNotesRequest(
	List<UserNoteItem> notes,
	List<WrongAnswerItem> wrongAnswers
) {
	public record UserNoteItem(
		String clientId,
		Long materialId,
		Integer pageNumber,
		String title,
		String content,
		String createdAt
	) {
	}

	public record WrongAnswerItem(
		String clientId,
		String quizResultRef,
		String memo
	) {
	}
}
