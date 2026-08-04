package io.edupilot.classroom.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.classroom.ClassroomWeekStatus;

public record ClassroomWeekResponse(
	Long weekId,
	int weekNumber,
	String title,
	ClassroomWeekStatus status,
	int displayOrder,
	Instant releaseAt,
	List<ClassroomWeekMaterialResponse> materials
) {
}
