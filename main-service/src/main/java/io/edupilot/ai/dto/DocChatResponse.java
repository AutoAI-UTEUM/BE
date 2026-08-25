package io.edupilot.ai.dto;

import java.util.List;

public record DocChatResponse(
	String schemaVersion,
	String answer,
	List<Warning> warnings
) {

	public record Warning(String type, String message) {
	}
}
