package io.edupilot.classroom.dto;

public record ClassroomAnalyticsMaterialResponse(
	Long materialId,
	String title,
	long viewerCount,
	int viewRate,
	int averageProgressRate
) {
}
