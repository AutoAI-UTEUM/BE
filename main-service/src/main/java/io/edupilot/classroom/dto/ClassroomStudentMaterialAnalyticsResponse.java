package io.edupilot.classroom.dto;

import java.time.Instant;

public record ClassroomStudentMaterialAnalyticsResponse(
	Long materialId,
	String title,
	Integer weekNumber,
	int progressRate,
	boolean viewed,
	Integer lastViewedPage,
	Instant lastViewedAt
) {
}
