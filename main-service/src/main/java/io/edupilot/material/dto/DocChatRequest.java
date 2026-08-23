package io.edupilot.material.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocChatRequest(
	@NotBlank @Size(max = 2000) String question,
	@Size(max = 50) List<@Valid HistoryMessage> history
) {

	public record HistoryMessage(
		@NotNull Role role,
		@NotBlank String content
	) {
	}

	public enum Role {
		USER,
		ASSISTANT
	}
}
