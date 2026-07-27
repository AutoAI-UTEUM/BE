package io.edupilot.session.dto;

import io.edupilot.session.PageStatus;

public record TurnStateResponse(
	int currentPage,
	PageStatus pageStatus,
	Long activeQuizId
) {
}
