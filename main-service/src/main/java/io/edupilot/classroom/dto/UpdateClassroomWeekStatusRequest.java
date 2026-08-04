package io.edupilot.classroom.dto;

import io.edupilot.classroom.ClassroomWeekStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateClassroomWeekStatusRequest(
	@NotNull ClassroomWeekStatus status
) {
}
