package io.edupilot.user.dto;

import io.edupilot.user.AiAnswerStyle;
import io.edupilot.user.User;

public record UserPreferencesResponse(
	boolean newMaterialNotification,
	boolean studyReminder,
	AiAnswerStyle aiAnswerStyle
) {
	public static UserPreferencesResponse from(User user) {
		return new UserPreferencesResponse(
			user.isNewMaterialNotification(),
			user.isStudyReminder(),
			user.getAiAnswerStyle()
		);
	}
}
