package io.edupilot.classroom.dto;

import java.util.List;

public record ClassroomListResponse(
	List<ClassroomSummaryResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
