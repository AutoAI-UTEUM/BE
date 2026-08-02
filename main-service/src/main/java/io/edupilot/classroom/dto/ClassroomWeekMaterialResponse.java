package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;

public record ClassroomWeekMaterialResponse(
	Long materialId,
	String title,
	Integer pageCount,
	MaterialProcessingStatus processingStatus,
	Instant uploadedAt
) {
	public static ClassroomWeekMaterialResponse from(LearningMaterial material) {
		return new ClassroomWeekMaterialResponse(
			material.getId(),
			material.getTitle(),
			material.getPageCount(),
			material.getProcessingStatus(),
			material.getCreatedAt()
		);
	}
}
