package io.edupilot.classroom.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.edupilot.classroom.ClassroomJoinRequest;

public record JoinRequestListResponse(
	List<JoinRequestResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	public static JoinRequestListResponse from(Page<ClassroomJoinRequest> requests) {
		return new JoinRequestListResponse(
			requests.getContent().stream().map(JoinRequestResponse::from).toList(),
			requests.getNumber(),
			requests.getSize(),
			requests.getTotalElements(),
			requests.getTotalPages()
		);
	}
}
