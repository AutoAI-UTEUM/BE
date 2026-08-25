package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@Component
public class QuizGenerationValidator {

	private static final Logger log =
		LoggerFactory.getLogger(QuizGenerationValidator.class);
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
		if (generation == null) {
			throw invalid("quiz generation is null");
		}
		if (!SCHEMA_VERSION.equals(turnSchemaVersion)) {
			throw invalid("turn schemaVersion mismatch");
		}
		if (generation.schemaVersion() != null
			&& !turnSchemaVersion.equals(generation.schemaVersion())) {
			throw invalid("quiz schemaVersion does not match turn schemaVersion");
		}
		if (!StringUtils.hasText(generation.generationId())) {
			throw invalid("generationId must not be blank");
		}
		if (!StringUtils.hasText(generation.quizType())) {
			throw invalid("quizType must not be blank");
		}
		if (!StringUtils.hasText(generation.title())) {
			throw invalid("title must not be blank");
		}
		if (generation.title().trim().length() > 255) {
			throw invalid(
				"title length exceeds limit: max=255 actual=%d"
					.formatted(generation.title().trim().length())
			);
		}
		if (generation.coverage() == null) {
			throw invalid("coverage must not be null");
		}
		if (generation.questionCount() == null) {
			throw invalid("questionCount must not be null");
		}
		if (generation.questions() == null) {
			throw invalid("questions must not be null");
		}

		QuizType quizType = quizType(generation.quizType());
		if (expectedQuizType != null
			&& !expectedQuizType.equals(quizType.name())) {
			throw invalid("quizType does not match requested quiz type");
		}
		validateCoverage(generation.coverage(), availablePages);
		if (generation.questionCount() < MIN_QUESTION_COUNT
			|| generation.questionCount() > MAX_QUESTION_COUNT) {
			throw invalid(
				"questionCount out of range: min=%d max=%d actual=%d"
					.formatted(
						MIN_QUESTION_COUNT,
						MAX_QUESTION_COUNT,
						generation.questionCount()
					)
			);
		}
		if (generation.questions().size() != generation.questionCount()) {
			throw invalid(
				"questionCount mismatch: declared=%d actual=%d"
					.formatted(
						generation.questionCount(),
						generation.questions().size()
					)
			);
		}

		Set<String> questionIds = new HashSet<>();
		for (int index = 0; index < generation.questions().size(); index++) {
			validateQuestion(
				generation.questions().get(index),
				quizType,
				questionIds,
				index
			);
		}
	}

	private void validateCoverage(
		QuizGeneration.Coverage coverage,
		Set<Integer> availablePages
	) {
		Integer startPage = coverage.startPage();
		Integer endPage = coverage.endPage();
		if (startPage == null) {
			throw invalid("coverage.startPage must not be null");
		}
		if (endPage == null) {
			throw invalid("coverage.endPage must not be null");
		}
		if (startPage < 1) {
			throw invalid(
				"coverage.startPage must be positive: actual=%d"
					.formatted(startPage)
			);
		}
		if (endPage < startPage) {
			throw invalid(
				"coverage range is reversed: startPage=%d endPage=%d"
					.formatted(startPage, endPage)
			);
		}
		if (availablePages == null) {
			throw invalid("available snapshot pages must not be null");
		}
		if (availablePages.isEmpty()) {
			throw invalid("available snapshot pages must not be empty");
		}
		for (int page = startPage; page <= endPage; page++) {
			if (!availablePages.contains(page)) {
				throw invalid(
					"coverage page is outside snapshot: page=%d"
						.formatted(page)
				);
			}
		}
	}

	private void validateQuestion(
		QuizGeneration.Question question,
		QuizType quizType,
		Set<String> questionIds,
		int questionIndex
	) {
		if (question == null) {
			throw invalid(
				"questions[%d] must not be null".formatted(questionIndex)
			);
		}
		if (!StringUtils.hasText(question.questionId())) {
			throw invalid(
				"questions[%d].questionId must not be blank"
					.formatted(questionIndex)
			);
		}
		if (!questionIds.add(question.questionId().trim())) {
			throw invalid(
				"questions[%d].questionId is duplicated"
					.formatted(questionIndex)
			);
		}
		if (!StringUtils.hasText(question.questionText())) {
			throw invalid(
				"questions[%d].questionText must not be blank"
					.formatted(questionIndex)
			);
		}
		validatePoints(question.points(), questionIndex);

		switch (quizType) {
			case MCQ -> validateMcq(question, questionIndex);
			case OX -> validateOx(question, questionIndex);
			case SHORT -> validateShort(question, questionIndex);
			case ESSAY -> validateEssay(question, questionIndex);
		}
	}

	private void validatePoints(BigDecimal points, int questionIndex) {
		if (points == null) {
			throw invalid(
				"questions[%d].points must not be null"
					.formatted(questionIndex)
			);
		}
		if (points.compareTo(BigDecimal.ZERO) <= 0) {
			throw invalid(
				"questions[%d].points must be positive"
					.formatted(questionIndex)
			);
		}
		if (points.precision() > 10) {
			throw invalid(
				"questions[%d].points precision exceeds limit: max=10 actual=%d"
					.formatted(questionIndex, points.precision())
			);
		}
		int scale = Math.max(0, points.stripTrailingZeros().scale());
		if (scale > 2) {
			throw invalid(
				"questions[%d].points scale exceeds limit: max=2 actual=%d"
					.formatted(questionIndex, scale)
			);
		}
	}

	private void validateMcq(
		QuizGeneration.Question question,
		int questionIndex
	) {
		List<QuizGeneration.Choice> choices = question.choices();
		if (choices == null) {
			throw invalid(
				"questions[%d].choices must not be null for MCQ"
					.formatted(questionIndex)
			);
		}
		if (choices.size() < 2) {
			throw invalid(
				"questions[%d].choices has too few items: min=2 actual=%d"
					.formatted(questionIndex, choices.size())
			);
		}
		if (!StringUtils.hasText(question.answerChoiceId())) {
			throw invalid(
				"questions[%d].answerChoiceId must not be blank for MCQ"
					.formatted(questionIndex)
			);
		}
		if (!StringUtils.hasText(question.explanation())) {
			throw invalid(
				"questions[%d].explanation must not be blank for MCQ"
					.formatted(questionIndex)
			);
		}
		validateAbsent(
			question.answerValue(),
			"answerValue",
			QuizType.MCQ,
			questionIndex
		);
		validateAbsent(
			question.referenceAnswer(),
			"referenceAnswer",
			QuizType.MCQ,
			questionIndex
		);
		validateAbsent(
			question.gradingCriteria(),
			"gradingCriteria",
			QuizType.MCQ,
			questionIndex
		);
		validateAbsent(
			question.modelAnswer(),
			"modelAnswer",
			QuizType.MCQ,
			questionIndex
		);
		validateAbsent(
			question.rubric(),
			"rubric",
			QuizType.MCQ,
			questionIndex
		);
		Set<String> choiceIds = new HashSet<>();
		for (int index = 0; index < choices.size(); index++) {
			QuizGeneration.Choice choice = choices.get(index);
			if (choice == null) {
				throw invalid(
					"questions[%d].choices[%d] must not be null"
						.formatted(questionIndex, index)
				);
			}
			if (!StringUtils.hasText(choice.choiceId())) {
				throw invalid(
					"questions[%d].choices[%d].choiceId must not be blank"
						.formatted(questionIndex, index)
				);
			}
			if (!choiceIds.add(choice.choiceId().trim())) {
				throw invalid(
					"questions[%d].choices[%d].choiceId is duplicated"
						.formatted(questionIndex, index)
				);
			}
			if (!StringUtils.hasText(choice.text())) {
				throw invalid(
					"questions[%d].choices[%d].text must not be blank"
						.formatted(questionIndex, index)
				);
			}
		}
		if (!choiceIds.contains(question.answerChoiceId().trim())) {
			throw invalid(
				"questions[%d].answerChoiceId does not reference a choice"
					.formatted(questionIndex)
			);
		}
	}

	private void validateOx(
		QuizGeneration.Question question,
		int questionIndex
	) {
		if (question.answerValue() == null) {
			throw invalid(
				"questions[%d].answerValue must not be null for OX"
					.formatted(questionIndex)
			);
		}
		if (!StringUtils.hasText(question.explanation())) {
			throw invalid(
				"questions[%d].explanation must not be blank for OX"
					.formatted(questionIndex)
			);
		}
		validateAbsent(
			question.choices(),
			"choices",
			QuizType.OX,
			questionIndex
		);
		validateAbsent(
			question.answerChoiceId(),
			"answerChoiceId",
			QuizType.OX,
			questionIndex
		);
		validateAbsent(
			question.referenceAnswer(),
			"referenceAnswer",
			QuizType.OX,
			questionIndex
		);
		validateAbsent(
			question.gradingCriteria(),
			"gradingCriteria",
			QuizType.OX,
			questionIndex
		);
		validateAbsent(
			question.modelAnswer(),
			"modelAnswer",
			QuizType.OX,
			questionIndex
		);
		validateAbsent(
			question.rubric(),
			"rubric",
			QuizType.OX,
			questionIndex
		);
	}

	private void validateShort(
		QuizGeneration.Question question,
		int questionIndex
	) {
		if (!StringUtils.hasText(question.referenceAnswer())) {
			throw invalid(
				"questions[%d].referenceAnswer must not be blank for SHORT"
					.formatted(questionIndex)
			);
		}
		if (question.gradingCriteria() == null) {
			throw invalid(
				"questions[%d].gradingCriteria must not be null for SHORT"
					.formatted(questionIndex)
			);
		}
		if (question.gradingCriteria().isEmpty()) {
			throw invalid(
				"questions[%d].gradingCriteria must not be empty for SHORT"
					.formatted(questionIndex)
			);
		}
		for (int index = 0;
			index < question.gradingCriteria().size();
			index++) {
			if (!StringUtils.hasText(
				question.gradingCriteria().get(index)
			)) {
				throw invalid(
					"questions[%d].gradingCriteria[%d] must not be blank"
						.formatted(questionIndex, index)
				);
			}
		}
		validateAbsent(
			question.choices(),
			"choices",
			QuizType.SHORT,
			questionIndex
		);
		validateAbsent(
			question.answerChoiceId(),
			"answerChoiceId",
			QuizType.SHORT,
			questionIndex
		);
		validateAbsent(
			question.explanation(),
			"explanation",
			QuizType.SHORT,
			questionIndex
		);
		validateAbsent(
			question.answerValue(),
			"answerValue",
			QuizType.SHORT,
			questionIndex
		);
		validateAbsent(
			question.modelAnswer(),
			"modelAnswer",
			QuizType.SHORT,
			questionIndex
		);
		validateAbsent(
			question.rubric(),
			"rubric",
			QuizType.SHORT,
			questionIndex
		);
	}

	private void validateEssay(
		QuizGeneration.Question question,
		int questionIndex
	) {
		if (!StringUtils.hasText(question.modelAnswer())) {
			throw invalid(
				"questions[%d].modelAnswer must not be blank for ESSAY"
					.formatted(questionIndex)
			);
		}
		if (question.rubric() == null) {
			throw invalid(
				"questions[%d].rubric must not be null for ESSAY"
					.formatted(questionIndex)
			);
		}
		if (question.rubric().isEmpty()) {
			throw invalid(
				"questions[%d].rubric must not be empty for ESSAY"
					.formatted(questionIndex)
			);
		}
		validateAbsent(
			question.choices(),
			"choices",
			QuizType.ESSAY,
			questionIndex
		);
		validateAbsent(
			question.answerChoiceId(),
			"answerChoiceId",
			QuizType.ESSAY,
			questionIndex
		);
		validateAbsent(
			question.explanation(),
			"explanation",
			QuizType.ESSAY,
			questionIndex
		);
		validateAbsent(
			question.answerValue(),
			"answerValue",
			QuizType.ESSAY,
			questionIndex
		);
		validateAbsent(
			question.referenceAnswer(),
			"referenceAnswer",
			QuizType.ESSAY,
			questionIndex
		);
		validateAbsent(
			question.gradingCriteria(),
			"gradingCriteria",
			QuizType.ESSAY,
			questionIndex
		);
		BigDecimal totalWeight = BigDecimal.ZERO;
		for (int index = 0; index < question.rubric().size(); index++) {
			QuizGeneration.Rubric rubric = question.rubric().get(index);
			if (rubric == null) {
				throw invalid(
					"questions[%d].rubric[%d] must not be null"
						.formatted(questionIndex, index)
				);
			}
			if (!StringUtils.hasText(rubric.criterion())) {
				throw invalid(
					"questions[%d].rubric[%d].criterion must not be blank"
						.formatted(questionIndex, index)
				);
			}
			if (rubric.weight() == null) {
				throw invalid(
					"questions[%d].rubric[%d].weight must not be null"
						.formatted(questionIndex, index)
				);
			}
			if (rubric.weight().compareTo(BigDecimal.ZERO) <= 0
				|| rubric.weight().compareTo(BigDecimal.ONE) > 0) {
				throw invalid(
					"questions[%d].rubric[%d].weight must be between 0 and 1"
						.formatted(questionIndex, index)
				);
			}
			totalWeight = totalWeight.add(rubric.weight());
		}
		if (totalWeight.subtract(BigDecimal.ONE).abs()
			.compareTo(RUBRIC_WEIGHT_TOLERANCE) > 0) {
			throw invalid(
				"rubric weight sum mismatch: expected=1 actual=%s"
					.formatted(totalWeight.toPlainString())
			);
		}
	}

	private void validateAbsent(
		Object value,
		String field,
		QuizType quizType,
		int questionIndex
	) {
		if (value != null) {
			throw invalid(
				"questions[%d].%s is not allowed for %s"
					.formatted(questionIndex, field, quizType.name())
			);
		}
	}

	private QuizType quizType(String value) {
		try {
			return QuizType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw invalid("quizType is unsupported");
		}
	}

	private BusinessException invalid(String reason) {
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue(
				"validator",
				QuizGenerationValidator.class.getSimpleName()
			)
			.addKeyValue("errorCode", ErrorCode.AI_RESPONSE_INVALID.code())
			.addKeyValue("reason", reason)
			.log("AI response validation rejected");
		return new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}
}
