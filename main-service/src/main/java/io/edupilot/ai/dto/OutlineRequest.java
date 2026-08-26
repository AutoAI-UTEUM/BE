package io.edupilot.ai.dto;

import java.util.List;

public record OutlineRequest(
	String schemaVersion,
	String xaiFileId,
	int totalPages,
	List<Page> pages
) {

	public record Page(
		int pageNumber,
		String text
	) {
	}
}
