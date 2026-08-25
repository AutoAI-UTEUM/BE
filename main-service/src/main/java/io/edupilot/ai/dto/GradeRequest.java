package io.edupilot.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record GradeRequest(
	String schemaVersion,
	Long quizId,
	String quizType,
	List<Item> items,
	List<StudentAnswer> studentAnswers,
	@JsonInclude(JsonInclude.Include.NON_NULL) PageContext pageContext,
	@JsonInclude(JsonInclude.Include.NON_NULL) String learnerMemoryDigest
) {

	public record Item(
		String questionId,
		String question,
		String modelAnswer,
		List<Rubric> rubric,
		BigDecimal maxScore
	) {
	}

	public record Rubric(
		String criterion,
		BigDecimal weight
	) {
	}

	public record StudentAnswer(
		String questionId,
		String answer
	) {
	}

	public record PageContext(
		int coverageStartPage,
		int coverageEndPage,
		String text
	) {
	}
}
