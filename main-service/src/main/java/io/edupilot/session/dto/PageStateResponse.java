package io.edupilot.session.dto;

import java.util.List;

import io.edupilot.session.LearningSession;
import io.edupilot.session.PageStatus;
import io.edupilot.session.UiAction;

public record PageStateResponse(
	Long sessionId,
	int currentPage,
	PageStatus pageStatus,
	List<UiAction> uiActions
) {
	public static PageStateResponse from(LearningSession session) {
		return new PageStateResponse(
			session.getId(),
			session.getCurrentPage(),
			session.getPageStatus(),
			session.getLastUiActions()
		);
	}
}
