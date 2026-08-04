package io.edupilot.classroom.dto;

import java.time.LocalDate;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomStatus;

public record ClassroomSummaryResponse(
	Long classroomId,
	String name,
	String instructorName,
	LocalDate startDate,
	LocalDate endDate,
	int weekCount,
	ClassroomColor color,
	ClassroomStatus status,
	int currentWeek,
	long learnerCount,
	long materialCount,
	Integer progressRate,
	ClassroomLastStudiedResponse lastStudied,
	Long pendingRequestCount
) {
	public static ClassroomSummaryResponse from(
		Classroom classroom,
		boolean ownerView,
		int currentWeek,
		long learnerCount,
		long materialCount,
		long pendingRequestCount,
		Integer progressRate,
		ClassroomLastStudiedResponse lastStudied
	) {
		return new ClassroomSummaryResponse(
			classroom.getId(),
			classroom.getName(),
			classroom.getInstructorName(),
			classroom.getStartDate(),
			classroom.getEndDate(),
			classroom.getWeekCount(),
			classroom.getColor(),
			classroom.getStatus(),
			currentWeek,
			learnerCount,
			materialCount,
			ownerView ? null : progressRate,
			ownerView ? null : lastStudied,
			ownerView ? pendingRequestCount : null
		);
	}
}
