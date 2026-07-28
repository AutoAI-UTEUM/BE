package io.edupilot.material.dto;

import io.edupilot.material.MaterialPage;

public record MaterialPageResponse(
	int pageNumber,
	String text
) {
	public static MaterialPageResponse from(MaterialPage page) {
		return new MaterialPageResponse(page.getPageNumber(), page.getTextContent());
	}
}
