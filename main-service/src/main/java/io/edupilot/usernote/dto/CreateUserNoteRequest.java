package io.edupilot.usernote.dto;

public record CreateUserNoteRequest(
	Long materialId,
	Integer pageNumber,
	String title,
	String content,
	String clientId
) {
}
