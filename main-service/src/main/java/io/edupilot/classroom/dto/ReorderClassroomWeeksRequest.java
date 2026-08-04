package io.edupilot.classroom.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ReorderClassroomWeeksRequest(
	@NotEmpty List<@NotNull Long> orderedWeekIds
) {
}
