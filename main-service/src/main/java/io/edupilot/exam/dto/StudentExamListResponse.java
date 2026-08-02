package io.edupilot.exam.dto;

import java.util.List;

public record StudentExamListResponse(
	List<StudentExamListItemResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
