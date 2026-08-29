package io.edupilot.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record AiUsage(
	String model,
	@JsonAlias("input_tokens")
	Long inputTokens,
	@JsonAlias("output_tokens")
	Long outputTokens,
	@JsonAlias("reasoning_tokens")
	Long reasoningTokens
) {
}
