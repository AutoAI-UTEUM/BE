package io.edupilot.session.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.edupilot.session.UiAction;

public record TurnResponse(
	String turnId,
	Long sessionId,
	List<MessageResponse> messages,
	List<UiAction> uiActions,
	TurnStateResponse state,
	@JsonInclude(JsonInclude.Include.NON_NULL) NoteDraft noteDraft
) {

	public TurnResponse(
		String turnId,
		Long sessionId,
		List<MessageResponse> messages,
		List<UiAction> uiActions,
		TurnStateResponse state
	) {
		this(turnId, sessionId, messages, uiActions, state, null);
	}
}
