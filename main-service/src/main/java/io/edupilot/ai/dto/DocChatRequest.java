package io.edupilot.ai.dto;

import java.util.List;

public record DocChatRequest(
	String schemaVersion,
	List<ContextDocument> contextDocs,
	List<HistoryMessage> history,
	String question
) {

	public record ContextDocument(String title, String text) {
	}

	public record HistoryMessage(String role, String content) {
	}
}
