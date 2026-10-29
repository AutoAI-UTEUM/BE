package io.edupilot.ai;

import io.edupilot.ai.dto.QuizQuestionPreview;

public record TurnStreamEvent(
	Type type,
	String stage,
	String text,
	QuizQuestionPreview quizQuestion
) {

	public enum Type {
		STATUS,
		THOUGHT_SUMMARY,
		CONTENT_DELTA,
		HEARTBEAT,
		QUIZ_QUESTION
	}

	public static TurnStreamEvent status(String stage) {
		return new TurnStreamEvent(Type.STATUS, stage, null, null);
	}

	public static TurnStreamEvent thoughtSummary(String text) {
		return new TurnStreamEvent(Type.THOUGHT_SUMMARY, null, text, null);
	}

	public static TurnStreamEvent contentDelta(String text) {
		return new TurnStreamEvent(Type.CONTENT_DELTA, null, text, null);
	}

	public static TurnStreamEvent heartbeat() {
		return new TurnStreamEvent(Type.HEARTBEAT, null, null, null);
	}

	public static TurnStreamEvent quizQuestion(QuizQuestionPreview question) {
		return new TurnStreamEvent(Type.QUIZ_QUESTION, null, null, question);
	}
}
