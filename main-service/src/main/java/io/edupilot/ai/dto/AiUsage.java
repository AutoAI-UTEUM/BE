package io.edupilot.ai.dto;

public record AiUsage(
	String model,
	Long inputTokens,
	Long outputTokens,
	Long reasoningTokens
) {
}
