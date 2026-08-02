package io.edupilot.classroom.dto;

import io.edupilot.user.User;

public record JoinRequestLearnerResponse(
	Long userId,
	String name,
	String email,
	String affiliation
) {
	public static JoinRequestLearnerResponse from(User user) {
		return new JoinRequestLearnerResponse(
			user.getId(),
			user.getName(),
			user.getEmail(),
			user.getAffiliation()
		);
	}
}
