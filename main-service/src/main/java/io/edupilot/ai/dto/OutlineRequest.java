package io.edupilot.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record OutlineRequest(
	String schemaVersion,
	String xaiFileId,
	int totalPages,
	List<Page> pages,
	@JsonInclude(JsonInclude.Include.NON_NULL) Boolean includePageQuizPlan
) {
	public OutlineRequest(
		String schemaVersion,
		String xaiFileId,
		int totalPages,
		List<Page> pages
	) {
		this(schemaVersion, xaiFileId, totalPages, pages, null);
	}

	public record Page(
		int pageNumber,
		String text
	) {
	}
}
