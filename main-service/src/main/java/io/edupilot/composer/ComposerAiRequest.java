package io.edupilot.composer;

import java.util.List;

/** Adapter for the merged AI v1.0 contract, independent of provisional FE names. */
public record ComposerAiRequest(String schemaVersion, String sourceTurnId,
	String contextVersion, Activity learningActivity, List<Message> messages) {
	public ComposerAiRequest {
		messages = List.copyOf(messages);
	}
	public enum Activity { EXPLAIN, QA, QUIZ, DIAGNOSIS, TEACHBACK, OTHER }
	public enum Role { USER, ASSISTANT }
	public record Message(Role role, String content) { }
}
