package io.edupilot.session;

public interface ConversationSummaryTask {

	void summarize(Long sessionId, String traceId);
}
