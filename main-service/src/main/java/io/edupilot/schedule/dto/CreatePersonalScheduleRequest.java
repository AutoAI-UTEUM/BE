package io.edupilot.schedule.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePersonalScheduleRequest(
	@NotBlank @Size(max = 200) String title,
	@NotNull Instant startsAt,
	@NotNull Instant endsAt,
	@NotNull Boolean hasTime
) {
}
