package io.edupilot.material.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.material.LearningMaterial;

public record MaterialListResponse(
	List<MaterialSummaryResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static MaterialListResponse from(Page<LearningMaterial> materials) {
		return new MaterialListResponse(
			materials.getContent().stream()
				.map(MaterialSummaryResponse::from)
				.toList(),
			materials.getNumber(),
			materials.getSize(),
			materials.getTotalElements(),
			materials.getTotalPages()
		);
	}
}
