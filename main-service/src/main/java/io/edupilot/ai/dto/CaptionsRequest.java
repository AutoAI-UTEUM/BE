package io.edupilot.ai.dto;

import java.util.List;

public record CaptionsRequest(
	String schemaVersion,
	List<Page> pages
) {

	public record Page(
		int pageNumber,
		String imageBase64,
		String extractedText
	) {
	}
}
