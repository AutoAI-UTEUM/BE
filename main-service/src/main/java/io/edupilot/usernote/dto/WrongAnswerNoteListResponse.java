package io.edupilot.usernote.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.usernote.WrongAnswerNote;

public record WrongAnswerNoteListResponse(
	List<WrongAnswerNoteResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static WrongAnswerNoteListResponse from(Page<WrongAnswerNote> notes) {
		return new WrongAnswerNoteListResponse(
			notes.getContent().stream().map(WrongAnswerNoteResponse::from).toList(),
			notes.getNumber(), notes.getSize(),
			notes.getTotalElements(), notes.getTotalPages()
		);
	}
}
