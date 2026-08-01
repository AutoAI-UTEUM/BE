package io.edupilot.classroom.dto;

public record InviteCodeResponse(
	Long classroomId,
	String inviteCode
) {
}
