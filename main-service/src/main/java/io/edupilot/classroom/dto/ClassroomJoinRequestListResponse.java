package io.edupilot.classroom.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.classroom.ClassroomJoinRequest;

public record ClassroomJoinRequestListResponse(
	List<ClassroomJoinRequestResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static ClassroomJoinRequestListResponse from(
		Page<ClassroomJoinRequest> requests
	) {
		return new ClassroomJoinRequestListResponse(
			requests.getContent().stream()
				.map(ClassroomJoinRequestResponse::from)
				.toList(),
			requests.getNumber(),
			requests.getSize(),
			requests.getTotalElements(),
			requests.getTotalPages()
		);
	}
}
