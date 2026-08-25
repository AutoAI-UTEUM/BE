package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomJoinRequest;
import io.edupilot.classroom.ClassroomJoinRequestStatus;

public record JoinRequestProcessResponse(
	Long requestId,
	Long classroomId,
	ClassroomJoinRequestStatus status,
	Instant processedAt
) {
	public static JoinRequestProcessResponse from(ClassroomJoinRequest request) {
		return new JoinRequestProcessResponse(
			request.getId(),
			request.getClassroomId(),
			request.getStatus(),
			request.getProcessedAt()
		);
	}
}
