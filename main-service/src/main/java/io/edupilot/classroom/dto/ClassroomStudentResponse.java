package io.edupilot.classroom.dto;

import java.time.Instant;

public record ClassroomStudentResponse(
	Long studentId,
	String name,
	String email,
	String affiliation,
	Instant joinedAt,
	String status,
	Instant lastActiveAt,
	int averageProgressRate,
	long aiQuestionCountLast7Days,
	long quizSubmissionCount
) {
	public ClassroomStudentResponse(
		Long studentId,
		String name,
		String email,
		String affiliation,
		Instant joinedAt,
		String status,
		Instant lastActiveAt
	) {
		this(
			studentId,
			name,
			email,
			affiliation,
			joinedAt,
			status,
			lastActiveAt,
			0,
			0,
			0
		);
	}
}
