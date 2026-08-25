package io.edupilot.ai.dto;

import java.util.List;

public record ExtractResponse(
	String schemaVersion,
	int pageCount,
	List<ExtractedPage> pages,
	String xaiFileId,
	List<Warning> warnings
) {
	public ExtractResponse {
		warnings = warnings == null ? List.of() : warnings;
	}

	public ExtractResponse(
		String schemaVersion,
		int pageCount,
		List<ExtractedPage> pages
	) {
		this(schemaVersion, pageCount, pages, null, List.of());
	}

	public record Warning(String type, String message) {
	}
}
