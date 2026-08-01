package io.edupilot.classroom.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.classroom.ClassroomWeekStatus;

public record ClassroomWeekResponse(
	int weekNumber,
	String title,
	ClassroomWeekStatus status,
	Instant releaseAt,
	List<ClassroomWeekMaterialResponse> materials
) {
}
