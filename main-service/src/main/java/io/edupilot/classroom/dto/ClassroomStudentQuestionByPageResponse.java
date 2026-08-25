package io.edupilot.classroom.dto;

public record ClassroomStudentQuestionByPageResponse(
	Long materialId,
	String materialTitle,
	Integer weekNumber,
	int pageNumber,
	long questionCount
) {
}
