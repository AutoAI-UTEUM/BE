package io.edupilot.exam.dto;

import java.util.List;

public record InstructorExamListResponse(
	List<InstructorExamListItemResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
