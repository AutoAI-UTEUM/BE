package io.edupilot.classroom.dto;

import java.util.List;

public record ClassroomStudentListResponse(
	List<ClassroomStudentResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
