package io.edupilot.admin.xai.dto;

import java.time.Instant;

import io.edupilot.admin.xai.XaiManagementFailureType;

public record AdminXaiStatusResponse(
	boolean available,
	Instant lastSuccessfulSyncAt,
	Instant lastFailureAt,
	XaiManagementFailureType recentErrorClassification
) {

	public static AdminXaiStatusResponse unavailable() {
		return new AdminXaiStatusResponse(false, null, null, null);
	}
}
