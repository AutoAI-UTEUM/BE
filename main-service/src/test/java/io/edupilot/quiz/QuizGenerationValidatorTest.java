package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

class QuizGenerationValidatorTest {

	private static final Set<Integer> AVAILABLE_PAGES = Set.of(2, 3, 4);
	private static final String SENTINEL = "SENTINEL_PRIVATE_AI_VALUE";

	private final QuizGenerationValidator validator =
		new QuizGenerationValidator();

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectionCases")
	void logsSpecificReasonWithoutExposingRawValues(
		String name,
		RejectionCase rejection
	) {
		Logger logger =
			(Logger) LoggerFactory.getLogger(QuizGenerationValidator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-quiz-rejection");
		try {
			assertThatThrownBy(() -> validator.validate(
				rejection.generation(),
				rejection.turnSchemaVersion(),
				rejection.expectedQuizType(),
				rejection.availablePages()
			)).isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
					assertThat(exception.clientMessage())
						.isEqualTo(ErrorCode.AI_RESPONSE_INVALID.message());
				}
			);
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"AI response validation rejected"
			))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(logFields(event))
					.containsEntry("traceId", "trace-quiz-rejection")
					.containsEntry(
						"validator",
						"QuizGenerationValidator"
					)
					.containsEntry(
						"errorCode",
						"AI_RESPONSE_INVALID"
					)
					.containsEntry("reason", rejection.expectedReason());
				assertThat(event.getFormattedMessage())
					.doesNotContain(SENTINEL);
				assertThat(event.getKeyValuePairs().toString())
					.doesNotContain(SENTINEL);
			});
	}

	private static Stream<Arguments> rejectionCases() {
		QuizGeneration mcq = generation(QuizType.MCQ);
		QuizGeneration ox = generation(QuizType.OX);
		QuizGeneration shortQuiz = generation(QuizType.SHORT);
		QuizGeneration essay = generation(QuizType.ESSAY);

		List<QuizGeneration.Question> duplicateQuestions =
			new ArrayList<>(mcq.questions());
		duplicateQuestions.set(
			1,
			copyQuestion(
				duplicateQuestions.get(1),
				duplicateQuestions.getFirst().questionId()
			)
		);

		List<QuizGeneration.Question> scaledPoints =
			new ArrayList<>(mcq.questions());
		scaledPoints.set(
			0,
			copyQuestion(
				scaledPoints.getFirst(),
				new BigDecimal("1.001")
			)
		);

		List<QuizGeneration.Question> missingAnswer =
			new ArrayList<>(mcq.questions());
		QuizGeneration.Question mcqQuestion = missingAnswer.getFirst();
		missingAnswer.set(0, new QuizGeneration.Question(
			mcqQuestion.questionId(),
			mcqQuestion.questionText(),
			mcqQuestion.points(),
			mcqQuestion.choices(),
			"missing",
			mcqQuestion.explanation(),
			null,
			null,
			null,
			null,
			null
		));

		List<QuizGeneration.Question> oxBlankExplanation =
			new ArrayList<>(ox.questions());
		QuizGeneration.Question oxQuestion = oxBlankExplanation.getFirst();
		oxBlankExplanation.set(0, new QuizGeneration.Question(
			oxQuestion.questionId(),
			oxQuestion.questionText(),
			oxQuestion.points(),
			null,
			null,
			" ",
			oxQuestion.answerValue(),
			null,
			null,
			null,
			null
		));

		List<QuizGeneration.Question> shortBlankCriterion =
			new ArrayList<>(shortQuiz.questions());
		QuizGeneration.Question shortQuestion =
			shortBlankCriterion.getFirst();
		shortBlankCriterion.set(0, new QuizGeneration.Question(
			shortQuestion.questionId(),
			shortQuestion.questionText(),
			shortQuestion.points(),
			null,
			null,
			null,
			null,
			shortQuestion.referenceAnswer(),
			List.of(" "),
			null,
			null
		));

		List<QuizGeneration.Question> invalidRubric =
			new ArrayList<>(essay.questions());
		QuizGeneration.Question essayQuestion = invalidRubric.getFirst();
		invalidRubric.set(0, new QuizGeneration.Question(
			essayQuestion.questionId(),
			essayQuestion.questionText(),
			essayQuestion.points(),
			null,
			null,
			null,
			null,
			null,
			null,
			essayQuestion.modelAnswer(),
			List.of(
				new QuizGeneration.Rubric(
					"정확성",
					new BigDecimal("0.7")
				),
				new QuizGeneration.Rubric(
					"논리성",
					new BigDecimal("0.2")
				)
			)
		));

		List<QuizGeneration.Question> privateSentinel =
			new ArrayList<>(mcq.questions());
		QuizGeneration.Question sentinelQuestion =
			privateSentinel.getFirst();
		privateSentinel.set(0, new QuizGeneration.Question(
			sentinelQuestion.questionId(),
			sentinelQuestion.questionText(),
			sentinelQuestion.points(),
			sentinelQuestion.choices(),
			sentinelQuestion.answerChoiceId(),
			sentinelQuestion.explanation(),
			null,
			null,
			null,
			SENTINEL,
			null
		));

		return Stream.of(
			arguments(
				"null generation",
				new RejectionCase(
					null,
					"1.0",
					null,
					AVAILABLE_PAGES,
					"quiz generation is null"
				)
			),
			arguments(
				"turn schema mismatch",
				rejection(mcq, "2.0", null, "turn schemaVersion mismatch")
			),
			arguments(
				"requested quiz type mismatch",
				rejection(
					mcq,
					"1.0",
					"OX",
					"quizType does not match requested quiz type"
				)
			),
			arguments(
				"unsupported quiz type",
				rejection(
					copy(mcq, SENTINEL, mcq.coverage(), 5, mcq.questions()),
					"1.0",
					null,
					"quizType is unsupported"
				)
			),
			arguments(
				"question count mismatch",
				rejection(
					copy(mcq, "MCQ", mcq.coverage(), 6, mcq.questions()),
					"questionCount mismatch: declared=6 actual=5"
				)
			),
			arguments(
				"coverage outside snapshot",
				rejection(
					copy(
						mcq,
						"MCQ",
						new QuizGeneration.Coverage(1, 3),
						5,
						mcq.questions()
					),
					"coverage page is outside snapshot: page=1"
				)
			),
			arguments(
				"duplicate question id",
				rejection(
					copy(
						mcq,
						"MCQ",
						mcq.coverage(),
						5,
						duplicateQuestions
					),
					"questions[1].questionId is duplicated"
				)
			),
			arguments(
				"points scale",
				rejection(
					copy(
						mcq,
						"MCQ",
						mcq.coverage(),
						5,
						scaledPoints
					),
					"questions[0].points scale exceeds limit: max=2 actual=3"
				)
			),
			arguments(
				"MCQ answer reference",
				rejection(
					copy(
						mcq,
						"MCQ",
						mcq.coverage(),
						5,
						missingAnswer
					),
					"questions[0].answerChoiceId does not reference a choice"
				)
			),
			arguments(
				"OX explanation",
				rejection(
					copy(
						ox,
						"OX",
						ox.coverage(),
						5,
						oxBlankExplanation
					),
					"questions[0].explanation must not be blank for OX"
				)
			),
			arguments(
				"SHORT grading criterion",
				rejection(
					copy(
						shortQuiz,
						"SHORT",
						shortQuiz.coverage(),
						5,
						shortBlankCriterion
					),
					"questions[0].gradingCriteria[0] must not be blank"
				)
			),
			arguments(
				"ESSAY rubric sum",
				rejection(
					copy(
						essay,
						"ESSAY",
						essay.coverage(),
						5,
						invalidRubric
					),
					"rubric weight sum mismatch: expected=1 actual=0.9"
				)
			),
			arguments(
				"private value is not logged",
				rejection(
					copy(
						mcq,
						"MCQ",
						mcq.coverage(),
						5,
						privateSentinel
					),
					"questions[0].modelAnswer is not allowed for MCQ"
				)
			)
		);
	}

	private static Arguments arguments(
		String name,
		RejectionCase rejection
	) {
		return Arguments.of(name, rejection);
	}

	private static RejectionCase rejection(
		QuizGeneration generation,
		String expectedReason
	) {
		return rejection(generation, "1.0", generation.quizType(), expectedReason);
	}

	private static RejectionCase rejection(
		QuizGeneration generation,
		String turnSchemaVersion,
		String expectedQuizType,
		String expectedReason
	) {
		return new RejectionCase(
			generation,
			turnSchemaVersion,
			expectedQuizType,
			AVAILABLE_PAGES,
			expectedReason
		);
	}

	private static QuizGeneration generation(QuizType quizType) {
		return new QuizGeneration(
			"1.0",
			"generation-1",
			quizType.name(),
			new QuizGeneration.Coverage(2, 4),
			"퀴즈",
			5,
			IntStream.rangeClosed(1, 5)
				.mapToObj(index -> question(quizType, index))
				.toList()
		);
	}

	private static QuizGeneration.Question question(
		QuizType quizType,
		int index
	) {
		String questionId = "q" + index;
		BigDecimal points = new BigDecimal("20.00");
		return switch (quizType) {
			case MCQ -> new QuizGeneration.Question(
				questionId,
				"문항",
				points,
				List.of(
					new QuizGeneration.Choice("a", "A"),
					new QuizGeneration.Choice("b", "B")
				),
				"a",
				"설명",
				null,
				null,
				null,
				null,
				null
			);
			case OX -> new QuizGeneration.Question(
				questionId,
				"문항",
				points,
				null,
				null,
				"설명",
				true,
				null,
				null,
				null,
				null
			);
			case SHORT -> new QuizGeneration.Question(
				questionId,
				"문항",
				points,
				null,
				null,
				null,
				null,
				"기준 답안",
				List.of("정확성"),
				null,
				null
			);
			case ESSAY -> new QuizGeneration.Question(
				questionId,
				"문항",
				points,
				null,
				null,
				null,
				null,
				null,
				null,
				"모범 답안",
				List.of(
					new QuizGeneration.Rubric(
						"정확성",
						BigDecimal.ONE
					)
				)
			);
		};
	}

	private static QuizGeneration copy(
		QuizGeneration source,
		String quizType,
		QuizGeneration.Coverage coverage,
		int questionCount,
		List<QuizGeneration.Question> questions
	) {
		return new QuizGeneration(
			source.schemaVersion(),
			source.generationId(),
			quizType,
			coverage,
			source.title(),
			questionCount,
			List.copyOf(questions)
		);
	}

	private static QuizGeneration.Question copyQuestion(
		QuizGeneration.Question source,
		String questionId
	) {
		return new QuizGeneration.Question(
			questionId,
			source.questionText(),
			source.points(),
			source.choices(),
			source.answerChoiceId(),
			source.explanation(),
			source.answerValue(),
			source.referenceAnswer(),
			source.gradingCriteria(),
			source.modelAnswer(),
			source.rubric()
		);
	}

	private static QuizGeneration.Question copyQuestion(
		QuizGeneration.Question source,
		BigDecimal points
	) {
		return new QuizGeneration.Question(
			source.questionId(),
			source.questionText(),
			points,
			source.choices(),
			source.answerChoiceId(),
			source.explanation(),
			source.answerValue(),
			source.referenceAnswer(),
			source.gradingCriteria(),
			source.modelAnswer(),
			source.rubric()
		);
	}

	private static Map<String, String> logFields(ILoggingEvent event) {
		return event.getKeyValuePairs().stream()
			.collect(java.util.stream.Collectors.toMap(
				pair -> pair.key,
				pair -> String.valueOf(pair.value)
			));
	}

	private record RejectionCase(
		QuizGeneration generation,
		String turnSchemaVersion,
		String expectedQuizType,
		Set<Integer> availablePages,
		String expectedReason
	) {
	}
}
