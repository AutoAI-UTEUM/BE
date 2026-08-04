package io.edupilot.classroom.dto;

public record ClassroomQuestionByPageResponse(
	Long materialId,
	int pageNumber,
	long questionCount
) {
}
