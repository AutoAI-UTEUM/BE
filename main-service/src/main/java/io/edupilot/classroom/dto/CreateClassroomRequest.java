package io.edupilot.classroom.dto;

import java.time.LocalDate;

import io.edupilot.classroom.ClassroomColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(
	@NotBlank @Size(max = 100) String name,
	@NotNull LocalDate startDate,
	@NotNull LocalDate endDate,
	@NotNull ClassroomColor color,
	@Size(max = 255) String description
) {
}
