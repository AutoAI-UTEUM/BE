package io.edupilot.admin.dto;

import io.edupilot.aiusage.AiFeature;

public record AdminAiUsageFeatureResponse(
	AiFeature feature,
	long callCount,
	Long inputTokens,
	Long outputTokens,
	Long reasoningTokens
) {
}
