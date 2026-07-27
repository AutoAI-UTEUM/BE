package io.edupilot.global.config;

public record ReadinessResponse(
	Status status,
	Checks checks
) {

	public static ReadinessResponse of(
		boolean databaseUp,
		boolean aiServiceUp
	) {
		CheckStatus database = databaseUp
			? CheckStatus.UP
			: CheckStatus.DOWN;
		CheckStatus aiService = aiServiceUp
			? CheckStatus.UP
			: CheckStatus.DOWN;
		Status overall = !databaseUp
			? Status.DOWN
			: aiServiceUp ? Status.UP : Status.DEGRADED;
		return new ReadinessResponse(
			overall,
			new Checks(database, aiService)
		);
	}

	public boolean unavailable() {
		return status == Status.DOWN;
	}

	public enum Status {
		UP,
		DEGRADED,
		DOWN
	}

	public enum CheckStatus {
		UP,
		DOWN
	}

	public record Checks(
		CheckStatus db,
		CheckStatus aiService
	) {
	}
}
