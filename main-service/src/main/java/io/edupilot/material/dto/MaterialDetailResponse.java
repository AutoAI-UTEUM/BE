package io.edupilot.material.dto;

import java.time.Instant;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialFailureReason;
import io.edupilot.material.MaterialProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record MaterialDetailResponse(
	Long materialId,
	String title,
	Integer pageCount,
	MaterialProcessingStatus processingStatus,
	boolean learningAvailable,
	@Schema(nullable = true)
	MaterialFailureReason failureReason,
	@Schema(nullable = true, maxLength = 64)
	String traceId,
	Instant createdAt
) {
	public static MaterialDetailResponse from(LearningMaterial material) {
		return new MaterialDetailResponse(
			material.getId(),
			material.getTitle(),
			material.getPageCount(),
			material.getProcessingStatus(),
			material.isReady(),
			material.getProcessingStatus() == MaterialProcessingStatus.FAILED
				? material.getFailureReason()
				: null,
			material.getProcessingStatus() == MaterialProcessingStatus.FAILED
				? material.getFailureTraceId()
				: null,
			material.getCreatedAt()
		);
	}
}
