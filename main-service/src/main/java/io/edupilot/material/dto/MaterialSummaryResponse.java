package io.edupilot.material.dto;

import java.time.Instant;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;

public record MaterialSummaryResponse(
	Long materialId,
	String title,
	Integer pageCount,
	MaterialProcessingStatus processingStatus,
	Instant createdAt
) {
	public static MaterialSummaryResponse from(LearningMaterial material) {
		return new MaterialSummaryResponse(
			material.getId(),
			material.getTitle(),
			material.getPageCount(),
			material.getProcessingStatus(),
			material.getCreatedAt()
		);
	}
}
