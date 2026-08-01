package io.edupilot.user;

public enum AiAnswerStyle {
	CONCISE("NORMAL"),
	NORMAL("NORMAL"),
	DETAILED("DETAILED");

	private final String detailLevel;

	AiAnswerStyle(String detailLevel) {
		this.detailLevel = detailLevel;
	}

	public String detailLevel() {
		return detailLevel;
	}
}
