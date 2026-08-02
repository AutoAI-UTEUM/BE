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
	Integer progressRate,
	ClassroomLastStudiedResponse lastStudied,
	Long pendingRequestCount
) {
	public static ClassroomSummaryResponse from(
		Classroom classroom,
		boolean ownerView,
		int currentWeek,
		long learnerCount,
		long pendingRequestCount
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
			ownerView ? null : 0, // TODO(#129): 공개 주차 자료 진도율 연결
			null, // TODO(#129): 공개 주차 자료의 최근 학습 세션 연결
			ownerView ? pendingRequestCount : null
		);
	}
}
