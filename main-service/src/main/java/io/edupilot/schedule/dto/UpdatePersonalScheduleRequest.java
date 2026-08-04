package io.edupilot.schedule.dto;

import java.time.Instant;

import jakarta.validation.constraints.Size;

public record UpdatePersonalScheduleRequest(
	@Size(min = 1, max = 200) String title,
	Instant startsAt,
	Instant endsAt,
	Boolean hasTime
) {
	public boolean hasAnyChange() {
		return title != null || startsAt != null || endsAt != null || hasTime != null;
	}
}
