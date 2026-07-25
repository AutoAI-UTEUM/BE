package io.edupilot.session.dto;

import java.time.Instant;
import java.util.List;

import io.edupilot.session.LearningSession;
import io.edupilot.session.PageStatus;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;

public record SessionDetailResponse(
	Long sessionId,
	Long materialId,
	int currentPage,
	PageStatus pageStatus,
	SessionStatus status,
	Object pendingDiagnosis,
	Long activeQuizId,
	List<UiAction> uiActions,
	Instant updatedAt
) {
	public static SessionDetailResponse from(LearningSession session) {
		return new SessionDetailResponse(
			session.getId(),
			session.getMaterialId(),
			session.getCurrentPage(),
			session.getPageStatus(),
			session.getStatus(),
			null,
			session.getActiveQuizId(),
			session.getLastUiActions(),
			session.getUpdatedAt()
		);
	}
}
