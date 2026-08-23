package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.dto.NoteDraft;
import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.quiz.QuizGenerationValidator;

class TurnResponseValidatorTest {

	private final TurnResponseValidator validator =
		new TurnResponseValidator(new QuizGenerationValidator());

	@Test
	void acceptsSystemMessageAndFollowUpThreadReference() {
		validator.validate(
			response(
				List.of(Map.of(
					"messageType",
					"SYSTEM",
					"content",
					"안내"
				)),
				Map.of(
					"qaThread",
					Map.of(
						"mode",
						"FOLLOW_UP",
						"threadRef",
						"qa-30"
					)
				)
			),
			"turn-1",
			"qa-30"
		);
	}

	@Test
	void validatesOptionalNoteDraft() {
		validator.validate(
			responseWithNoteDraft(new NoteDraft("복습 노트", "## 핵심\n내용")),
			"turn-1"
		);

		assertThatThrownBy(() -> validator.validate(
			responseWithNoteDraft(new NoteDraft("가".repeat(61), "내용")),
			"turn-1"
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);
		assertThatThrownBy(() -> validator.validate(
			responseWithNoteDraft(new NoteDraft("복습 노트", "  ")),
			"turn-1"
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);
	}

	@Test
	void enforcesExactQaThreadPatchAndSnapshotReference() {
		validator.validate(
			response(
				List.of(),
				Map.of("qaThread", Map.of("mode", "START_NEW"))
			),
			"turn-1"
		);
		assertPolicy(Map.of(
			"qaThread",
			Map.of("mode", "START_NEW", "threadRef", "qa-30")
		));
		assertPolicy(Map.of(
			"qaThread",
			Map.of("mode", "FOLLOW_UP")
		));
		assertThatThrownBy(() -> validator.validate(
			response(
				List.of(),
				Map.of(
					"qaThread",
					Map.of("mode", "FOLLOW_UP", "threadRef", "qa-31")
				)
			),
			"turn-1",
			"qa-30"
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_POLICY_REJECTED)
		);
	}

	@Test
	void ignoresAiUiActionsAndWarnsWithoutLoggingTheirValue() {
		String sentinel = "SENTINEL_RAW_UI_ACTION";
		Logger logger =
			(Logger) LoggerFactory.getLogger(TurnResponseValidator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-74");
		try {
			validator.validate(
				response(
					List.of(),
					Map.of(),
					List.of(Map.of(
						"type", "BINARY_DECISION",
						"content", sentinel,
						"yesEvent", "COMPLETE_SESSION",
						"noEvent", "WAIT"
					))
				),
				"turn-1"
			);
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Ignored non-empty AI uiActions"
			))
			.singleElement()
			.satisfies(event -> {
				Map<String, String> fields = event.getKeyValuePairs().stream()
					.collect(Collectors.toMap(
						pair -> pair.key,
						pair -> String.valueOf(pair.value)
					));
				assertThat(fields)
					.containsEntry("traceId", "trace-74")
					.containsEntry("turnId", "turn-1")
					.containsEntry("uiActionCount", "1");
				assertThat(event.getFormattedMessage())
					.doesNotContain(sentinel);
				assertThat(event.getKeyValuePairs().toString())
					.doesNotContain(sentinel);
			});
	}

	@Test
	void rejectsUnknownPatchAndNotExplainedRegression() {
		assertPolicy(Map.of("status", "COMPLETED"));
		assertPolicy(Map.of("pageStatus", "NOT_EXPLAINED"));
	}

	@Test
	void logsInvalidAndPolicyReasonsWithoutExposingRawValues() {
		String sentinel = "SENTINEL_REJECTED_AI_VALUE";
		Logger logger =
			(Logger) LoggerFactory.getLogger(TurnResponseValidator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-rejection");
		try {
			assertThatThrownBy(() -> validator.validate(
				response(
					List.of(Map.of(
						"messageType",
						sentinel,
						"content",
						"내용"
					)),
					Map.of()
				),
				"turn-1"
			)).isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
					assertThat(exception.clientMessage())
						.isEqualTo(ErrorCode.AI_RESPONSE_INVALID.message());
				}
			);
			assertThatThrownBy(() -> validator.validate(
				response(
					List.of(),
					Map.of("pageStatus", sentinel)
				),
				"turn-1"
			)).isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.AI_POLICY_REJECTED);
					assertThat(exception.clientMessage())
						.isEqualTo(ErrorCode.AI_POLICY_REJECTED.message());
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
			.hasSize(2)
			.allSatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(logFields(event))
					.containsEntry("traceId", "trace-rejection")
					.containsEntry(
						"validator",
						"TurnResponseValidator"
					);
				assertThat(event.getFormattedMessage())
					.doesNotContain(sentinel);
				assertThat(event.getKeyValuePairs().toString())
					.doesNotContain(sentinel);
			});
		assertThat(appender.list)
			.filteredOn(event -> logFields(event).get("errorCode")
				.equals("AI_RESPONSE_INVALID"))
			.singleElement()
			.satisfies(event ->
				assertThat(logFields(event))
					.containsEntry(
						"reason",
						"messages[0].messageType is unsupported"
					)
			);
		assertThat(appender.list)
			.filteredOn(event -> logFields(event).get("errorCode")
				.equals("AI_POLICY_REJECTED"))
			.singleElement()
			.satisfies(event ->
				assertThat(logFields(event))
					.containsEntry(
						"reason",
						"statePatch.pageStatus is unsupported"
					)
			);
	}

	@Test
	void acceptsValidMemoryCandidate() {
		validator.validate(
			responseWithMemoryCandidates(List.of(memoryCandidate())),
			"turn-1"
		);
	}

	@Test
	void rejectsInvalidMemoryCandidateContract() {
		assertInvalidMemoryCandidate(candidateWith("type", "UNKNOWN"));
		assertInvalidMemoryCandidate(
			candidateWith("confidence", new BigDecimal("1.01"))
		);
		assertInvalidMemoryCandidate(candidateWith("evidence", List.of()));
		assertInvalidMemoryCandidate(
			candidateWith("evidence", List.of("same", " same "))
		);

		Map<String, Object> missingPromotionRequested =
			new LinkedHashMap<>(memoryCandidate());
		missingPromotionRequested.remove("promotionRequested");
		assertInvalidMemoryCandidate(missingPromotionRequested);
		assertInvalidMemoryCandidate(
			candidateWith("promotionRequested", "true")
		);
	}

	@Test
	void validatesAllQuizTypesAgainstEventAndSnapshotPages() {
		for (String quizType : List.of("MCQ", "OX", "SHORT", "ESSAY")) {
			validator.validate(
				response(
					List.of(),
					Map.of("pageStatus", "QUIZ_READY"),
					List.of(),
					quiz(quizType, 5, new QuizGeneration.Coverage(2, 4))
				),
				"turn-1",
				null,
				TurnEventType.QUIZ_TYPE_SELECTED,
				quizType,
				Set.of(2, 3, 4)
			);
		}
	}

	@Test
	void rejectsQuizPresenceTypeCountCoverageAndSchemaViolations() {
		assertInvalidQuiz(
			response(List.of(), Map.of()),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"MCQ"
		);
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				quiz("MCQ", 5, new QuizGeneration.Coverage(2, 4))
			),
			TurnEventType.USER_QUESTION,
			null
		);
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				quiz("MCQ", 5, new QuizGeneration.Coverage(2, 4))
			),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"OX"
		);
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				quiz("MCQ", 4, new QuizGeneration.Coverage(2, 4))
			),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"MCQ"
		);
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				quiz("MCQ", 5, new QuizGeneration.Coverage(1, 3))
			),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"MCQ"
		);

		QuizGeneration invalidAnswer = quiz(
			"MCQ",
			5,
			new QuizGeneration.Coverage(2, 4)
		);
		List<QuizGeneration.Question> answerQuestions =
			new java.util.ArrayList<>(invalidAnswer.questions());
		QuizGeneration.Question first = answerQuestions.getFirst();
		answerQuestions.set(0, new QuizGeneration.Question(
			first.questionId(),
			first.questionText(),
			first.points(),
			first.choices(),
			"missing",
			first.explanation(),
			null,
			null,
			null,
			null,
			null
		));
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				copy(invalidAnswer, answerQuestions)
			),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"MCQ"
		);

		QuizGeneration invalidRubric = quiz(
			"ESSAY",
			5,
			new QuizGeneration.Coverage(2, 4)
		);
		List<QuizGeneration.Question> essayQuestions =
			new java.util.ArrayList<>(invalidRubric.questions());
		QuizGeneration.Question essay = essayQuestions.getFirst();
		essayQuestions.set(0, new QuizGeneration.Question(
			essay.questionId(),
			essay.questionText(),
			essay.points(),
			null,
			null,
			null,
			null,
			null,
			null,
			essay.modelAnswer(),
			List.of(
				new QuizGeneration.Rubric("정확성", new BigDecimal("0.7")),
				new QuizGeneration.Rubric("논리성", new BigDecimal("0.2"))
			)
		));
		assertInvalidQuiz(
			response(
				List.of(),
				Map.of(),
				List.of(),
				copy(invalidRubric, essayQuestions)
			),
			TurnEventType.QUIZ_TYPE_SELECTED,
			"ESSAY"
		);
	}

	@Test
	void ignoresAiActiveQuizIdAndWarnsWithoutLoggingItsValue() {
		String sentinel = "SENTINEL_AI_QUIZ_ID";
		Logger logger =
			(Logger) LoggerFactory.getLogger(TurnResponseValidator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			validator.validate(
				response(List.of(), Map.of("activeQuizId", sentinel)),
				"turn-1"
			);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Ignored AI activeQuizId statePatch"
			))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getKeyValuePairs().toString())
					.doesNotContain(sentinel);
				assertThat(event.getFormattedMessage())
					.doesNotContain(sentinel);
			});
	}

	private void assertPolicy(Map<String, Object> patch) {
		assertThatThrownBy(() ->
			validator.validate(response(List.of(), patch), "turn-1"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_POLICY_REJECTED)
			);
	}

	private void assertInvalidMemoryCandidate(
		Map<String, Object> candidate
	) {
		assertThatThrownBy(() -> validator.validate(
			responseWithMemoryCandidates(List.of(candidate)),
			"turn-1"
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);
	}

	private io.edupilot.ai.dto.TurnResponse responseWithMemoryCandidates(
		List<Map<String, Object>> memoryCandidates
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"ANSWER",
			List.of(),
			List.of(),
			Map.of(),
			List.of(),
			null,
			memoryCandidates,
			null,
			null
		);
	}

	private io.edupilot.ai.dto.TurnResponse responseWithNoteDraft(
		NoteDraft noteDraft
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"WRITE_NOTE",
			List.of(),
			List.of(),
			Map.of(),
			List.of(),
			null,
			List.of(),
			null,
			noteDraft,
			null
		);
	}

	private Map<String, Object> memoryCandidate() {
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("type", "WEAKNESS");
		candidate.put("content", "분수 나눗셈 개념 보완 필요");
		candidate.put("confidence", new BigDecimal("0.70"));
		candidate.put("evidence", List.of("assessment-1", "qa-2"));
		candidate.put("promotionRequested", true);
		return candidate;
	}

	private Map<String, Object> candidateWith(
		String key,
		Object value
	) {
		Map<String, Object> candidate =
			new LinkedHashMap<>(memoryCandidate());
		candidate.put(key, value);
		return candidate;
	}

	private Map<String, String> logFields(ILoggingEvent event) {
		return event.getKeyValuePairs().stream()
			.collect(Collectors.toMap(
				pair -> pair.key,
				pair -> String.valueOf(pair.value)
			));
	}

	private io.edupilot.ai.dto.TurnResponse response(
		List<Map<String, Object>> messages,
		Map<String, Object> patch
	) {
		return response(messages, patch, List.of());
	}

	private io.edupilot.ai.dto.TurnResponse response(
		List<Map<String, Object>> messages,
		Map<String, Object> patch,
		List<Map<String, Object>> uiActions
	) {
		return response(messages, patch, uiActions, null);
	}

	private io.edupilot.ai.dto.TurnResponse response(
		List<Map<String, Object>> messages,
		Map<String, Object> patch,
		List<Map<String, Object>> uiActions,
		QuizGeneration quiz
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"ANSWER",
			List.of(),
			messages,
			patch,
			uiActions,
			quiz,
			List.of(),
			null,
			null
		);
	}

	private void assertInvalidQuiz(
		io.edupilot.ai.dto.TurnResponse response,
		TurnEventType eventType,
		String expectedQuizType
	) {
		assertThatThrownBy(() -> validator.validate(
			response,
			"turn-1",
			null,
			eventType,
			expectedQuizType,
			Set.of(2, 3, 4)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
		);
	}

	private QuizGeneration quiz(
		String quizType,
		int questionCount,
		QuizGeneration.Coverage coverage
	) {
		List<QuizGeneration.Question> questions = java.util.stream.IntStream
			.rangeClosed(1, 5)
			.mapToObj(index -> question(quizType, index))
			.toList();
		return new QuizGeneration(
			"1.0",
			"generation-1",
			quizType,
			coverage,
			"퀴즈",
			questionCount,
			questions
		);
	}

	private QuizGeneration.Question question(String quizType, int index) {
		String id = "q" + index;
		BigDecimal points = new BigDecimal("20.00");
		return switch (quizType) {
			case "MCQ" -> new QuizGeneration.Question(
				id,
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
			case "OX" -> new QuizGeneration.Question(
				id, "문항", points, null, null, "설명", true,
				null, null, null, null
			);
			case "SHORT" -> new QuizGeneration.Question(
				id, "문항", points, null, null, null, null,
				"기준 답안", List.of("정확성"), null, null
			);
			case "ESSAY" -> new QuizGeneration.Question(
				id, "문항", points, null, null, null, null,
				null, null, "모범 답안",
				List.of(
					new QuizGeneration.Rubric(
						"정확성",
						BigDecimal.ONE
					)
				)
			);
			default -> throw new IllegalArgumentException();
		};
	}

	private QuizGeneration copy(
		QuizGeneration source,
		List<QuizGeneration.Question> questions
	) {
		return new QuizGeneration(
			source.schemaVersion(),
			source.generationId(),
			source.quizType(),
			source.coverage(),
			source.title(),
			source.questionCount(),
			List.copyOf(questions)
		);
	}
}
