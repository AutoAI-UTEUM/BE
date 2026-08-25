package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.classroom.ClassroomJoinRequest;
import io.edupilot.classroom.ClassroomJoinRequestStatus;

public record ClassroomJoinRequestResponse(
	Long requestId,
	ClassroomJoinRequestStatus status,
	Instant requestedAt,
	Instant processedAt,
	JoinRequestLearnerResponse learner
) {
	public static ClassroomJoinRequestResponse from(ClassroomJoinRequest request) {
		return new ClassroomJoinRequestResponse(
			request.getId(),
			request.getStatus(),
			request.getRequestedAt(),
			request.getProcessedAt(),
			JoinRequestLearnerResponse.from(request.getUser())
		);
	}
}
