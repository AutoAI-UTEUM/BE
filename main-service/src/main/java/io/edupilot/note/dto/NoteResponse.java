package io.edupilot.note.dto;

import java.time.Instant;

import io.edupilot.note.Note;

public record NoteResponse(
	Long noteId,
	Long sessionId,
	Long materialId,
	String content,
	Integer pageNumber,
	Long sourceMessageId,
	Instant createdAt,
	Instant updatedAt
) {
	public static NoteResponse from(Note note) {
		return new NoteResponse(
			note.getId(),
			note.getSessionId(),
			note.getMaterialId(),
			note.getContent(),
			note.getPageNumber(),
			note.getSourceMessageId(),
			note.getCreatedAt(),
			note.getUpdatedAt()
		);
	}
}
