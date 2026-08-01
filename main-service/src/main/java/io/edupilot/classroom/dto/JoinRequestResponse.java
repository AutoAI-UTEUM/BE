package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomJoinRequest;
import io.edupilot.classroom.ClassroomJoinRequestStatus;

public record JoinRequestResponse(
	Long requestId,
	Long classroomId,
	String classroomName,
	ClassroomJoinRequestStatus status,
	Instant requestedAt,
	Instant processedAt
) {
	public static JoinRequestResponse from(ClassroomJoinRequest request) {
		return new JoinRequestResponse(
			request.getId(),
			request.getClassroomId(),
			request.getClassroomName(),
			request.getStatus(),
			request.getRequestedAt(),
			request.getProcessedAt()
		);
	}
}
