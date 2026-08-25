package io.edupilot.classroom.dto;

import java.time.Instant;

import io.edupilot.session.LearningSession;

public record ClassroomLastStudiedResponse(
	Long sessionId,
	Long materialId,
	String materialTitle,
	int pageNumber,
	Instant updatedAt
) {
	public static ClassroomLastStudiedResponse from(LearningSession session) {
		return new ClassroomLastStudiedResponse(
			session.getId(),
			session.getMaterialId(),
			session.getMaterialTitle(),
			session.getCurrentPage(),
			session.getUpdatedAt()
		);
	}
}
