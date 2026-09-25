package io.edupilot.usernote.dto;

public record CreateWrongAnswerNoteRequest(
	String quizResultRef,
	String memo,
	String clientId
) {
}
