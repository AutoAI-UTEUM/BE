package io.edupilot.composer;

import java.util.List;

import io.edupilot.ai.dto.AiUsage;

public record ComposerAiResponse(String schemaVersion, String sourceTurnId,
	String contextVersion, String suggestion, List<Warning> warnings, AiUsage usage) {
	public record Warning(String type, String message) { }

	public boolean validFor(ComposerAiRequest request) {
		return "1.0".equals(schemaVersion) && request.sourceTurnId().equals(sourceTurnId)
			&& request.contextVersion().equals(contextVersion) && warnings != null
			&& warnings.stream().allMatch(w -> w != null
				&& "PREDICTION_UNAVAILABLE".equals(w.type()))
			&& (suggestion == null || (!suggestion.isBlank()
				&& suggestion.codePointCount(0, suggestion.length()) <= 160
				&& suggestion.codePoints().noneMatch(Character::isISOControl)
				&& !suggestion.contains("\u2028") && !suggestion.contains("\u2029")));
	}
}
