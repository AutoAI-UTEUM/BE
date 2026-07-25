package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

public record GradeResponse(
	String schemaVersion,
	Long quizId,
	String quizType,
	BigDecimal score,
	BigDecimal maxScore,
	List<Item> items,
	Usage usage
) {

	public record Item(
		String questionId,
		BigDecimal score,
		BigDecimal maxScore,
		String verdict,
		String feedback
	) {
	}

	public record Usage(
		String model,
		Long inputTokens,
		Long outputTokens,
		Long reasoningTokens
	) {
	}
}
