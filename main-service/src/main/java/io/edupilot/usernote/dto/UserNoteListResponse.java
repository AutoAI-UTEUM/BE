package io.edupilot.usernote.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.usernote.UserNote;

public record UserNoteListResponse(
	List<UserNoteResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static UserNoteListResponse from(Page<UserNote> notes) {
		return new UserNoteListResponse(
			notes.getContent().stream().map(UserNoteResponse::from).toList(),
			notes.getNumber(), notes.getSize(),
			notes.getTotalElements(), notes.getTotalPages()
		);
	}
}
