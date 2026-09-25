package io.edupilot.usernote.dto;

import java.time.Instant;

import io.edupilot.usernote.UserNote;

public record UserNoteResponse(
	Long id,
	Long materialId,
	Integer pageNumber,
	String title,
	String content,
	Instant createdAt,
	Instant updatedAt
) {
	public static UserNoteResponse from(UserNote note) {
		return new UserNoteResponse(
			note.getId(), note.getMaterialId(), note.getPageNumber(),
			note.getTitle(), note.getContent(), note.getCreatedAt(), note.getUpdatedAt()
		);
	}
}
