package io.edupilot.schedule.dto;

import java.time.Instant;

import io.edupilot.schedule.ScheduleType;
import io.edupilot.schedule.UserSchedule;

public record PersonalScheduleResponse(
	String scheduleId,
	ScheduleType kind,
	String title,
	Instant startsAt,
	Instant endsAt,
	boolean hasTime
) {
	public static PersonalScheduleResponse from(UserSchedule schedule) {
		return new PersonalScheduleResponse(
			schedule.getId().toString(),
			ScheduleType.PERSONAL,
			schedule.getTitle(),
			schedule.getStartsAt(),
			schedule.getEndsAt(),
			schedule.hasTime()
		);
	}
}
