package io.edupilot.material.dto;

import java.util.List;

public record DocChatResponse(
	String answer,
	List<Warning> warnings
) {

	public record Warning(String type, String message) {
	}
}
