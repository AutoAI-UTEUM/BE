package io.edupilot.user.dto;

import io.edupilot.user.AiAnswerStyle;
import io.swagger.v3.oas.annotations.media.Schema;

public record UpdatePreferencesRequest(
	Boolean newMaterialNotification,
	Boolean studyReminder,
	@Schema(example = "NORMAL") AiAnswerStyle aiAnswerStyle
) {
}
