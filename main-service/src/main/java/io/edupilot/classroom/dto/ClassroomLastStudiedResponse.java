package io.edupilot.classroom.dto;

import java.time.Instant;

public record ClassroomLastStudiedResponse(
	Long sessionId,
	Long materialId,
	String materialTitle,
	int pageNumber,
	Instant updatedAt
) {
}
