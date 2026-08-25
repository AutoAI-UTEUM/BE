package io.edupilot.ai.dto;

import java.util.List;

public record CriteriaSuggestRequest(
	String schemaVersion,
	List<String> existingCriterionKeys,
	List<Material> materials
) {
	public record Material(
		String title,
		String materialSummary,
		List<OutlineResponse.Section> sections
	) {
	}
}
