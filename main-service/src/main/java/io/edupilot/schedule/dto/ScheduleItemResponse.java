package io.edupilot.schedule.dto;

import java.time.Instant;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomNotice;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.schedule.ScheduleType;

public record ScheduleItemResponse(
	String scheduleId,
	Instant dateTime,
	ScheduleType type,
	String title,
	Long classroomId,
	String classroomName,
	ClassroomColor color
) {
	public static ScheduleItemResponse from(ClassroomWeek week) {
		Classroom classroom = week.getClassroom();
		return new ScheduleItemResponse(
			"WEEK-" + week.getId(),
			week.getReleaseAt() == null
				? week.getCreatedAt()
				: week.getReleaseAt(),
			ScheduleType.WEEK_RELEASE,
			week.getWeekNumber() + "주차 공개: " + week.getTitle(),
			classroom.getId(),
			classroom.getName(),
			classroom.getColor()
		);
	}

	public static ScheduleItemResponse from(ClassroomNotice notice) {
		Classroom classroom = notice.getClassroom();
		return new ScheduleItemResponse(
			"NOTICE-" + notice.getId(),
			notice.getPublishedAt(),
			ScheduleType.NOTICE_PUBLISH,
			notice.getTitle(),
			classroom.getId(),
			classroom.getName(),
			classroom.getColor()
		);
	}
}
