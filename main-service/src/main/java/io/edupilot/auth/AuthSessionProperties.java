package io.edupilot.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.edupilot.user.UserRole;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.auth.session")
public record AuthSessionProperties(
	@NotNull Duration absoluteTtl,
	@NotNull Duration adminIdleTtl,
	@NotNull Duration instructorIdleTtl,
	@NotNull Duration learnerIdleTtl
) {

	public AuthSessionProperties {
		requirePositive(absoluteTtl, "absoluteTtl");
		requirePositive(adminIdleTtl, "adminIdleTtl");
		requirePositive(instructorIdleTtl, "instructorIdleTtl");
		requirePositive(learnerIdleTtl, "learnerIdleTtl");
	}

	public Duration idleTtl(UserRole role) {
		return switch (role) {
			case ADMIN -> adminIdleTtl;
			case INSTRUCTOR -> instructorIdleTtl;
			case LEARNER -> learnerIdleTtl;
		};
	}

	private static void requirePositive(Duration value, String name) {
		if (value != null && (value.isZero() || value.isNegative())) {
			throw new IllegalArgumentException(name + " must be positive.");
		}
	}
}
