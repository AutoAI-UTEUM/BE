package io.edupilot.session;

import java.time.Instant;

public record StudentLastActivity(
	Long studentId,
	Instant lastActiveAt
) {
}
