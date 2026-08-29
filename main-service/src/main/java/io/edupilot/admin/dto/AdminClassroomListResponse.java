package io.edupilot.admin.dto;

import java.util.List;

public record AdminClassroomListResponse(
	List<AdminClassroomResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
