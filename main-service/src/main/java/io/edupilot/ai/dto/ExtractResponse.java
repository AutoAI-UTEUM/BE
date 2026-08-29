package io.edupilot.ai.dto;

import java.util.List;

public record ExtractResponse(
	String schemaVersion,
	int pageCount,
	List<ExtractedPage> pages,
	String xaiFileId,
	List<Warning> warnings,
	AiUsage usage
) {
	public ExtractResponse {
		warnings = warnings == null ? List.of() : warnings;
	}

	public record Warning(String type, String message) {
	}
}
