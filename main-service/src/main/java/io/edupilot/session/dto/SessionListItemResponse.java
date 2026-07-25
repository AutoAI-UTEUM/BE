package io.edupilot.session.dto;

import java.time.Instant;

import io.edupilot.session.LearningSession;
import io.edupilot.session.SessionStatus;

public record SessionListItemResponse(
	Long sessionId,
	Long materialId,
	String materialTitle,
	int currentPage,
	SessionStatus status,
	Instant updatedAt
) {
	public static SessionListItemResponse from(LearningSession session) {
		return new SessionListItemResponse(
			session.getId(),
			session.getMaterialId(),
			session.getMaterialTitle(),
			session.getCurrentPage(),
			session.getStatus(),
			session.getUpdatedAt()
		);
	}
}
