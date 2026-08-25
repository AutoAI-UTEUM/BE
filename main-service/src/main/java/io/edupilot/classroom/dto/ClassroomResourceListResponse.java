package io.edupilot.classroom.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.classroom.ClassroomResource;

public record ClassroomResourceListResponse(
	List<ClassroomResourceResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static ClassroomResourceListResponse from(
		Page<ClassroomResource> resources
	) {
		return new ClassroomResourceListResponse(
			resources.getContent().stream()
				.map(ClassroomResourceResponse::from)
				.toList(),
			resources.getNumber(),
			resources.getSize(),
			resources.getTotalElements(),
			resources.getTotalPages()
		);
	}
}
