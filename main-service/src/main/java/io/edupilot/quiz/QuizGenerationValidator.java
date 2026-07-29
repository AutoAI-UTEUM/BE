package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class QuizGenerationValidator {

	private static final String SCHEMA_VERSION = "1.0";
	private static final int MIN_QUESTION_COUNT = 5;
	private static final int MAX_QUESTION_COUNT = 10;
	private static final BigDecimal RUBRIC_WEIGHT_TOLERANCE =
		new BigDecimal("0.000001");

	public void validate(
		QuizGeneration generation,
		String turnSchemaVersion,
		String expectedQuizType,
		Set<Integer> availablePages
	) {
		if (generation == null
			|| !SCHEMA_VERSION.equals(turnSchemaVersion)
			|| generation.schemaVersion() != null
				&& !turnSchemaVersion.equals(generation.schemaVersion())
			|| !StringUtils.hasText(generation.generationId())
			|| !StringUtils.hasText(generation.quizType())
			|| !StringUtils.hasText(generation.title())
			|| generation.title().trim().length() > 255
			|| generation.coverage() == null
			|| generation.questionCount() == null
			|| generation.questions() == null) {
			throw invalid();
		}

		QuizType quizType = quizType(generation.quizType());
		if (expectedQuizType != null
			&& !expectedQuizType.equals(quizType.name())) {
			throw invalid();
		}
		validateCoverage(generation.coverage(), availablePages);
		if (generation.questionCount() < MIN_QUESTION_COUNT
			|| generation.questionCount() > MAX_QUESTION_COUNT
			|| generation.questions().size() != generation.questionCount()) {
			throw invalid();
		}

		Set<String> questionIds = new HashSet<>();
		for (QuizGeneration.Question question : generation.questions()) {
			validateQuestion(question, quizType, questionIds);
		}
	}

	private void validateCoverage(
		QuizGeneration.Coverage coverage,
		Set<Integer> availablePages
	) {
		Integer startPage = coverage.startPage();
		Integer endPage = coverage.endPage();
		if (startPage == null
			|| endPage == null
			|| startPage < 1
			|| endPage < startPage
			|| availablePages == null
			|| availablePages.isEmpty()) {
			throw invalid();
		}
		for (int page = startPage; page <= endPage; page++) {
			if (!availablePages.contains(page)) {
				throw invalid();
			}
		}
	}

	private void validateQuestion(
		QuizGeneration.Question question,
		QuizType quizType,
		Set<String> questionIds
	) {
		if (question == null
			|| !StringUtils.hasText(question.questionId())
			|| !questionIds.add(question.questionId().trim())
			|| !StringUtils.hasText(question.questionText())
			|| !validPoints(question.points())) {
			throw invalid();
		}

		switch (quizType) {
			case MCQ -> validateMcq(question);
			case OX -> validateOx(question);
			case SHORT -> validateShort(question);
			case ESSAY -> validateEssay(question);
		}
	}

	private void validateMcq(QuizGeneration.Question question) {
		List<QuizGeneration.Choice> choices = question.choices();
		if (choices == null
			|| choices.size() < 2
			|| !StringUtils.hasText(question.answerChoiceId())
			|| !StringUtils.hasText(question.explanation())
			|| question.answerValue() != null
			|| question.referenceAnswer() != null
			|| question.gradingCriteria() != null
			|| question.modelAnswer() != null
			|| question.rubric() != null) {
			throw invalid();
		}
		Set<String> choiceIds = new HashSet<>();
		for (QuizGeneration.Choice choice : choices) {
			if (choice == null
				|| !StringUtils.hasText(choice.choiceId())
				|| !choiceIds.add(choice.choiceId().trim())
				|| !StringUtils.hasText(choice.text())) {
				throw invalid();
			}
		}
		if (!choiceIds.contains(question.answerChoiceId().trim())) {
			throw invalid();
		}
	}

	private void validateOx(QuizGeneration.Question question) {
		if (question.answerValue() == null
			|| !StringUtils.hasText(question.explanation())
			|| question.choices() != null
			|| question.answerChoiceId() != null
			|| question.referenceAnswer() != null
			|| question.gradingCriteria() != null
			|| question.modelAnswer() != null
			|| question.rubric() != null) {
			throw invalid();
		}
	}

	private void validateShort(QuizGeneration.Question question) {
		if (!StringUtils.hasText(question.referenceAnswer())
			|| question.gradingCriteria() == null
			|| question.gradingCriteria().isEmpty()
			|| question.gradingCriteria().stream()
				.anyMatch(value -> !StringUtils.hasText(value))
			|| question.choices() != null
			|| question.answerChoiceId() != null
			|| question.explanation() != null
			|| question.answerValue() != null
			|| question.modelAnswer() != null
			|| question.rubric() != null) {
			throw invalid();
		}
	}

	private void validateEssay(QuizGeneration.Question question) {
		if (!StringUtils.hasText(question.modelAnswer())
			|| question.rubric() == null
			|| question.rubric().isEmpty()
			|| question.choices() != null
			|| question.answerChoiceId() != null
			|| question.explanation() != null
			|| question.answerValue() != null
			|| question.referenceAnswer() != null
			|| question.gradingCriteria() != null) {
			throw invalid();
		}
		BigDecimal totalWeight = BigDecimal.ZERO;
		for (QuizGeneration.Rubric rubric : question.rubric()) {
			if (rubric == null
				|| !StringUtils.hasText(rubric.criterion())
				|| rubric.weight() == null
				|| rubric.weight().compareTo(BigDecimal.ZERO) <= 0
				|| rubric.weight().compareTo(BigDecimal.ONE) > 0) {
				throw invalid();
			}
			totalWeight = totalWeight.add(rubric.weight());
		}
		if (totalWeight.subtract(BigDecimal.ONE).abs()
			.compareTo(RUBRIC_WEIGHT_TOLERANCE) > 0) {
			throw invalid();
		}
	}

	private boolean validPoints(BigDecimal points) {
		return points != null
			&& points.compareTo(BigDecimal.ZERO) > 0
			&& points.precision() <= 10
			&& Math.max(0, points.stripTrailingZeros().scale()) <= 2;
	}

	private QuizType quizType(String value) {
		try {
			return QuizType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}
}
