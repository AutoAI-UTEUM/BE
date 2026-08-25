package io.edupilot.classroom.dto;

import java.time.LocalDate;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomStatus;

public record ClassroomDetailResponse(
	Long classroomId,
	String name,
	String instructorName,
	LocalDate startDate,
	LocalDate endDate,
	int weekCount,
	ClassroomColor color,
	String description,
	ClassroomStatus status,
	int currentWeek,
	long learnerCount,
	Integer progressRate,
	ClassroomLastStudiedResponse lastStudied,
	Long pendingRequestCount,
	String inviteCode
) {
	public static ClassroomDetailResponse from(
		Classroom classroom,
		boolean ownerView,
		int currentWeek,
		long learnerCount,
		long pendingRequestCount,
		Integer progressRate,
		ClassroomLastStudiedResponse lastStudied
	) {
		return new ClassroomDetailResponse(
			classroom.getId(),
			classroom.getName(),
			classroom.getInstructorName(),
			classroom.getStartDate(),
			classroom.getEndDate(),
			classroom.getWeekCount(),
			classroom.getColor(),
			classroom.getDescription(),
			classroom.getStatus(),
			currentWeek,
			learnerCount,
			ownerView ? null : progressRate,
			ownerView ? null : lastStudied,
			ownerView ? pendingRequestCount : null,
			ownerView ? classroom.getInviteCode() : null
		);
	}
}
