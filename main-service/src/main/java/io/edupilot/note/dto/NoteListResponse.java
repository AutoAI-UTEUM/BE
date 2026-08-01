package io.edupilot.note.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.note.Note;

public record NoteListResponse(
	List<NoteResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static NoteListResponse from(Page<Note> notes) {
		return new NoteListResponse(
			notes.getContent().stream()
				.map(NoteResponse::from)
				.toList(),
			notes.getNumber(),
			notes.getSize(),
			notes.getTotalElements(),
			notes.getTotalPages()
		);
	}
}
