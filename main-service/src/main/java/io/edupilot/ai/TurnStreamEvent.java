package io.edupilot.ai;

public record TurnStreamEvent(
	Type type,
	String stage,
	String text
) {

	public enum Type {
		STATUS,
		THOUGHT_SUMMARY,
		CONTENT_DELTA,
		HEARTBEAT
	}

	public static TurnStreamEvent status(String stage) {
		return new TurnStreamEvent(Type.STATUS, stage, null);
	}

	public static TurnStreamEvent thoughtSummary(String text) {
		return new TurnStreamEvent(Type.THOUGHT_SUMMARY, null, text);
	}

	public static TurnStreamEvent contentDelta(String text) {
		return new TurnStreamEvent(Type.CONTENT_DELTA, null, text);
	}

	public static TurnStreamEvent heartbeat() {
		return new TurnStreamEvent(Type.HEARTBEAT, null, null);
	}
}
