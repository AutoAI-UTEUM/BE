package io.edupilot.schedule.dto;

import java.time.Instant;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomNotice;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.schedule.ScheduleType;
import io.edupilot.schedule.UserSchedule;

public record ScheduleItemResponse(
	String scheduleId,
	Instant dateTime,
	ScheduleType type,
	ScheduleType kind,
	String title,
	Long classroomId,
	String classroomName,
	ClassroomColor color,
	Instant startsAt,
	Instant endsAt,
	Boolean hasTime
) {
	public static ScheduleItemResponse from(ClassroomWeek week) {
		Classroom classroom = week.getClassroom();
		return new ScheduleItemResponse(
			"WEEK-" + week.getId(),
			week.getReleaseAt() == null
				? week.getCreatedAt()
				: week.getReleaseAt(),
			ScheduleType.WEEK_RELEASE,
			ScheduleType.WEEK_RELEASE,
			week.getWeekNumber() + "주차 공개: " + week.getTitle(),
			classroom.getId(),
			classroom.getName(),
			classroom.getColor(),
			null,
			null,
			null
		);
	}

	public static ScheduleItemResponse from(ClassroomNotice notice) {
		Classroom classroom = notice.getClassroom();
		return new ScheduleItemResponse(
			"NOTICE-" + notice.getId(),
			notice.getPublishedAt(),
			ScheduleType.NOTICE_PUBLISH,
			ScheduleType.NOTICE_PUBLISH,
			notice.getTitle(),
			classroom.getId(),
			classroom.getName(),
			classroom.getColor(),
			null,
			null,
			null
		);
	}

	public static ScheduleItemResponse from(UserSchedule schedule) {
		return new ScheduleItemResponse(
			schedule.getId().toString(),
			schedule.getStartsAt(),
			ScheduleType.PERSONAL,
			ScheduleType.PERSONAL,
			schedule.getTitle(),
			null,
			null,
			null,
			schedule.getStartsAt(),
			schedule.getEndsAt(),
			schedule.hasTime()
		);
	}
}
