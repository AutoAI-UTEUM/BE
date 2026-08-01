package io.edupilot.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNoteRequest(
	@NotBlank(message = "노트 내용은 필수입니다.")
	@Size(max = 10000, message = "노트 내용은 10000자 이하여야 합니다.")
	String content
) {
}
