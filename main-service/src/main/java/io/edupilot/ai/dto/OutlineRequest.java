package io.edupilot.ai.dto;

import java.util.List;

public record OutlineRequest(
	String schemaVersion,
	int totalPages,
	List<Page> pages
) {

	public record Page(
		int pageNumber,
		String text
	) {
	}
}
