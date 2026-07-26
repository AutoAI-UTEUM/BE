package io.edupilot.session.dto;

public record PendingDiagnosisResponse(
	Long diagnosisId,
	String prompt
) {
}
