package io.edupilot.classroom.dto;

import java.time.Instant;

public record ClassroomStudentResponse(
	Long studentId,
	String name,
	String email,
	String affiliation,
	Instant joinedAt,
	String status,
	Instant lastActiveAt
) {
}
