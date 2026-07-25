package io.edupilot.session;

public record UiAction(
	String type,
	String content,
	String yesEvent,
	String noEvent
) {

	public static UiAction initialExplanation() {
		return new UiAction(
			"BINARY_DECISION",
			"강의를 시작할까요?",
			"EXPLAIN_CURRENT_PAGE",
			"WAIT"
		);
	}

	public static UiAction pageExplanation() {
		return new UiAction(
			"BINARY_DECISION",
			"현재 페이지를 설명할까요?",
			"EXPLAIN_CURRENT_PAGE",
			"WAIT"
		);
	}
}
