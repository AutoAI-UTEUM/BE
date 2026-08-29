package io.edupilot.admin.dto;

import java.time.Instant;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomStatus;

public record AdminClassroomResponse(
	Long id,
	String name,
	AdminClassroomInstructorResponse instructor,
	long memberCount,
	ClassroomStatus status,
	Instant createdAt
) {
	public static AdminClassroomResponse from(
		Classroom classroom,
		long memberCount
	) {
		return new AdminClassroomResponse(
			classroom.getId(),
			classroom.getName(),
			new AdminClassroomInstructorResponse(
				classroom.getInstructorId(),
				classroom.getInstructorName()
			),
			memberCount,
			classroom.getStatus(),
			classroom.getCreatedAt()
		);
	}
}
