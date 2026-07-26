package io.edupilot.diagnosis;

import java.util.List;

public record DiagnosisData(
	String schemaVersion,
	List<String> focusConcepts,
	List<String> suspectedMisconceptions,
	List<String> evidence,
	String repairHint
) {
}
