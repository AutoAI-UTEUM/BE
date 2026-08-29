package io.edupilot.admin.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomStatus;

public record AdminClassroomDetailResponse(
	Long id,
	String name,
	AdminClassroomInstructorResponse instructor,
	long memberCount,
	ClassroomStatus status,
	Instant createdAt,
	List<AdminClassroomMemberResponse> members
) {
	public static AdminClassroomDetailResponse from(
		Classroom classroom,
		List<AdminClassroomMemberResponse> members
	) {
		return new AdminClassroomDetailResponse(
			classroom.getId(),
			classroom.getName(),
			new AdminClassroomInstructorResponse(
				classroom.getInstructorId(),
				classroom.getInstructorName()
			),
			members.size(),
			classroom.getStatus(),
			classroom.getCreatedAt(),
			members
		);
	}
}
