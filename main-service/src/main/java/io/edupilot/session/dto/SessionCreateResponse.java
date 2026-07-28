package io.edupilot.session.dto;

import java.util.List;

import io.edupilot.session.LearningSession;
import io.edupilot.session.PageStatus;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;

public record SessionCreateResponse(
	Long sessionId,
	Long materialId,
	int currentPage,
	PageStatus pageStatus,
	SessionStatus status,
	boolean reused,
	List<UiAction> uiActions
) {
	public static SessionCreateResponse from(
		LearningSession session,
		boolean reused
	) {
		return new SessionCreateResponse(
			session.getId(),
			session.getMaterialId(),
			session.getCurrentPage(),
			session.getPageStatus(),
			session.getStatus(),
			reused,
			session.getLastUiActions()
		);
	}
}
