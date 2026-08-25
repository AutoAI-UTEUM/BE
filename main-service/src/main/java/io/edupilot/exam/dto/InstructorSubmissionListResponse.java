package io.edupilot.exam.dto;

import java.util.List;

public record InstructorSubmissionListResponse(
	List<InstructorSubmissionListItemResponse> items,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
}
