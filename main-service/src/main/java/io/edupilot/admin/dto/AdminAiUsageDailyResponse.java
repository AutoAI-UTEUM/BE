package io.edupilot.admin.dto;

import java.time.LocalDate;

public record AdminAiUsageDailyResponse(
	LocalDate date,
	long callCount,
	long successCount,
	long failCount,
	Long inputTokens,
	Long outputTokens,
	Long reasoningTokens
) {
}
