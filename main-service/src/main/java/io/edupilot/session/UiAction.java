package io.edupilot.session;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiAction(
	String type,
	String content,
	String yesEvent,
	String noEvent,
	Long diagnosisId
) {

	public static UiAction initialExplanation() {
		return new UiAction(
			"BINARY_DECISION",
			"강의를 시작할까요?",
			"EXPLAIN_CURRENT_PAGE",
			"WAIT",
			null
		);
	}

	public static UiAction pageExplanation() {
		return new UiAction(
			"BINARY_DECISION",
			"현재 페이지를 설명할까요?",
			"EXPLAIN_CURRENT_PAGE",
			"WAIT",
			null
		);
	}

	public static UiAction moveNextPage() {
		return new UiAction(
			"BINARY_DECISION",
			"다음 페이지로 이동할까요?",
			"MOVE_NEXT_PAGE",
			"WAIT",
			null
		);
	}

	public static UiAction quizProposal() {
		return new UiAction(
			"BINARY_DECISION",
			"퀴즈를 진행할까요?",
			"SHOW_QUIZ_TYPE_SELECT",
			"WAIT",
			null
		);
	}

	public static UiAction completeSession() {
		return new UiAction(
			"BINARY_DECISION",
			"학습을 완료할까요?",
			"COMPLETE_SESSION",
			"WAIT",
			null
		);
	}

	public static UiAction diagnosisQuestion(
		String diagnosticPrompt,
		Long diagnosisId
	) {
		return new UiAction(
			"DIAGNOSIS_QUESTION",
			diagnosticPrompt,
			null,
			null,
			diagnosisId
		);
	}
}
