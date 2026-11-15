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
	Instant createdAt,
	@Schema(description = "인증된 사용자 본인의 자료 설명 완료 진도율", minimum = "0", maximum = "100")
	int progressRate
) {
	public static MaterialDetailResponse from(LearningMaterial material, int progressRate) {
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
			material.getCreatedAt(),
			progressRate
		);
	}
}
