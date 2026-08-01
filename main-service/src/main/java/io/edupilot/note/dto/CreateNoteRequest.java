package io.edupilot.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateNoteRequest(
	@NotBlank(message = "노트 내용은 필수입니다.")
	@Size(max = 10000, message = "노트 내용은 10000자 이하여야 합니다.")
	String content,
	@Positive(message = "페이지 번호는 1 이상이어야 합니다.")
	Integer pageNumber,
	@Positive(message = "메시지 ID는 1 이상이어야 합니다.")
	Long sourceMessageId
) {
}
