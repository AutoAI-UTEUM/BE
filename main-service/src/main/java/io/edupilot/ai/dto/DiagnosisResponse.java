package io.edupilot.ai.dto;

import java.util.List;

public record DiagnosisResponse(
	String schemaVersion,
	List<String> focusConcepts,
	List<String> suspectedMisconceptions,
	String diagnosticPrompt,
	List<String> evidence,
	String repairHint,
	AiUsage usage
) {
}
