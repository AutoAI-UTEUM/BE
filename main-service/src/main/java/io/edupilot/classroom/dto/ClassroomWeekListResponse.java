package io.edupilot.classroom.dto;

import java.util.List;

public record ClassroomWeekListResponse(
	List<ClassroomWeekResponse> items
) {
}
