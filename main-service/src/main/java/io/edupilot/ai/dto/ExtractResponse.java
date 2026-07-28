package io.edupilot.ai.dto;

import java.util.List;

public record ExtractResponse(
	String schemaVersion,
	int pageCount,
	List<ExtractedPage> pages
) {
}
