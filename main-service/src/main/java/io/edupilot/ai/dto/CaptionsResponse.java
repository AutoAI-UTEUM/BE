package io.edupilot.ai.dto;

import java.util.List;

public record CaptionsResponse(
	String schemaVersion,
	List<PageCaption> captions,
	List<Warning> warnings,
	AiUsage usage
) {
	public record PageCaption(int pageNumber, String caption) {
	}

	public record Warning(String type, String message) {
	}
}
