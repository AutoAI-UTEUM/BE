package io.edupilot.classroom.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomWeekRequest(
	@Min(1) int weekNumber,
	@NotBlank @Size(max = 100) String title,
	Instant releaseAt
) {
}
