package io.edupilot.ai.dto;

import java.util.List;

public record OutlineResponse(
	String schemaVersion,
	String materialSummary,
	List<Section> sections,
	int totalPages
) {

	public record Section(
		String title,
		int startPage,
		int endPage,
		List<String> keywords
	) {
	}
}
