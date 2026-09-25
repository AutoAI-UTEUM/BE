package io.edupilot.usernote.dto;

import java.util.List;

public record ImportNotesResponse(
	int imported,
	int skipped,
	List<FailedItem> failed
) {
	public record FailedItem(String clientId, String reason) {
	}
}
