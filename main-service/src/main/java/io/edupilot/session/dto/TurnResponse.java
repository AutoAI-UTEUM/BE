package io.edupilot.session.dto;

import java.util.List;

import io.edupilot.session.UiAction;

public record TurnResponse(
	String turnId,
	Long sessionId,
	List<MessageResponse> messages,
	List<UiAction> uiActions,
	TurnStateResponse state
) {
}
