package io.edupilot.material.dto;

import java.time.Instant;

import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record MaterialOverviewResponse(
	Long materialId,
	@Schema(nullable = true)
	String content,
	@Schema(allowableValues = {"PENDING", "READY", "FAILED"})
	String status,
	@Schema(nullable = true)
	Instant updatedAt
) {
	public static MaterialOverviewResponse pending(Long materialId) {
		return new MaterialOverviewResponse(
			materialId,
			null,
			MaterialOverviewStatus.PENDING.name(),
			null
		);
	}

	public static MaterialOverviewResponse from(MaterialOverview overview) {
		return new MaterialOverviewResponse(
			overview.getMaterialId(),
			overview.getStatus() == MaterialOverviewStatus.READY
				? overview.getContent()
				: null,
			overview.getStatus().name(),
			overview.getUpdatedAt()
		);
	}
}
