package io.edupilot.admin.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomMember;
import io.edupilot.user.UserRole;

public record AdminClassroomMemberResponse(
	Long userId,
	String name,
	UserRole role,
	Instant joinedAt
) {
	public static AdminClassroomMemberResponse from(ClassroomMember member) {
		return new AdminClassroomMemberResponse(
			member.getUserId(),
			member.getUserName(),
			member.getUserRole(),
			member.getJoinedAt()
		);
	}
}
