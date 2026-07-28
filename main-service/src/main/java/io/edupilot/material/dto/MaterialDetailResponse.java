package io.edupilot.material.dto;

import java.time.Instant;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;

public record MaterialDetailResponse(
	Long materialId,
	String title,
	Integer pageCount,
	MaterialProcessingStatus processingStatus,
	boolean learningAvailable,
	Instant createdAt
) {
	public static MaterialDetailResponse from(LearningMaterial material) {
		return new MaterialDetailResponse(
			material.getId(),
			material.getTitle(),
			material.getPageCount(),
			material.getProcessingStatus(),
			material.isReady(),
			material.getCreatedAt()
		);
	}
}
