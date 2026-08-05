package io.edupilot.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PermanentDeleteClassroomRequest(
	@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "AI 기초")
	String confirmName
) {
}
