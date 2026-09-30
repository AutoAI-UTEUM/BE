package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.QuizQuestionStreamFixtures;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.NoteDraft;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SessionStreamConnectionTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@ParameterizedTest
	@ValueSource(strings = {"MCQ", "OX", "SHORT", "ESSAY"})
	void privateFieldsAtEveryDepthNeverReachSerializedSsePayload(String quizType) throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(1L, 100L, () -> {}, emitter);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);
		ObjectNode preview = (ObjectNode) objectMapper.valueToTree(
			QuizQuestionStreamFixtures.preview(1, quizType));
		ObjectNode question = (ObjectNode) preview.get("question");
		ObjectNode coverage = (ObjectNode) preview.get("coverage");
		List<String> privateKeys = List.of(
			"answer", "answerChoiceId", "answerValue", "correctChoice", "correctAnswer",
			"explanation", "referenceAnswer", "modelAnswer", "rubric", "gradingCriteria",
			"scoringHint", "solution", "score", "maxScore"
		);
		for (String key : privateKeys) {
			preview.put(key, "private-root-" + key);
			question.put(key, "private-question-" + key);
			coverage.put(key, "private-coverage-" + key);
		}
		question.set("internal", objectMapper.readTree(
			"{\"nested\":[{\"answer\":\"private-nested-answer\"}]}"));
		if ("MCQ".equals(quizType)) {
			for (var choice : question.get("choices")) {
				for (String key : privateKeys) {
					((ObjectNode) choice).put(key, "private-choice-" + key);
				}
			}
		} else {
			question.set("choices", objectMapper.readTree(
				"[{\"answer\":\"private-non-mcq-choice\"}]"));
		}

		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setHeader("Content-Type", "application/x-ndjson")
				.setBody(objectMapper.writeValueAsString(preview) + "\n"
					+ QuizQuestionStreamFixtures.completedJson("turn-quiz", quizType)));
			QuizQuestionStreamFixtures.client(server).executeTurnStream(
				new io.edupilot.ai.dto.TurnRequest("1.0", "turn-quiz",
					Map.of("sessionId", 100L), Map.of("eventType", "QUIZ_TYPE_SELECTED"), Map.of(),
					Map.of("qaQuizProposal", false, "quizQuestionStream", true)),
				connection::send, cancellation, java.time.Duration.ofSeconds(3)
			);
		}

		assertThat(emitter.eventNames()).containsExactly("quiz_question");
		String serialized = objectMapper.writeValueAsString(emitter.payload(0));
		for (String key : privateKeys) {
			assertThat(serialized).doesNotContain("\"" + key + "\"");
		}
		assertThat(serialized).doesNotContain("private-", "internal");
		JsonNode publicEvent = objectMapper.readTree(serialized);
		assertThat(publicEvent.get("questionIndex").intValue()).isEqualTo(1);
		assertThat(publicEvent.get("question").get("questionText").textValue()).isEqualTo("문항 1");
		assertThat(publicEvent.get("question").has("choices")).isEqualTo("MCQ".equals(quizType));
	}

	@Test
	void fiveQuestionStreamEndsWithPublicCompletedOnlyAndSavedQuizId() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(1L, 100L, () -> {}, emitter);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);
		String previews = java.util.stream.IntStream.rangeClosed(1, 5)
			.mapToObj(QuizQuestionStreamFixtures::previewJson)
			.collect(java.util.stream.Collectors.joining("\n"));
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setHeader("Content-Type", "application/x-ndjson")
				.setBody(previews + "\n" + QuizQuestionStreamFixtures.completedJson("turn-quiz")));
			var completed = QuizQuestionStreamFixtures.client(server).executeTurnStream(
				new io.edupilot.ai.dto.TurnRequest("1.0", "turn-quiz", Map.of("sessionId", 100L),
					Map.of("eventType", "QUIZ_TYPE_SELECTED"), Map.of(),
					Map.of("qaQuizProposal", false, "quizQuestionStream", true)),
				connection::send, cancellation, java.time.Duration.ofSeconds(3));
			assertThat(completed.quiz().questions()).hasSize(5);
			assertThat(emitter.eventNames()).containsExactly(
				"quiz_question", "quiz_question", "quiz_question", "quiz_question", "quiz_question");
		}
		connection.sendCompleted("request-quiz", new TurnResponse("turn-quiz", 100L, List.of(), List.of(),
			new TurnStateResponse(3, PageStatus.QUIZ_READY, 50L)));
		assertThat(emitter.eventNames()).containsExactly(
			"quiz_question", "quiz_question", "quiz_question", "quiz_question", "quiz_question", "completed");
		assertThat(objectMapper.writeValueAsString(emitter.payload(5)))
			.contains("\"activeQuizId\":50").doesNotContain("비공개", "answerChoiceId", "explanation", "statePatch");
	}

	@Test
	void emitsExactExternalOrderAndPublicPayloads() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.send(TurnStreamEvent.status("PLANNING"));
		connection.send(TurnStreamEvent.thoughtSummary("계획 중"));
		connection.send(TurnStreamEvent.contentDelta("답변"));
		connection.send(TurnStreamEvent.heartbeat());
		UiAction action = UiAction.quizProposal();
		connection.sendUiAction(action);
		TurnResponse response = response(action);
		connection.sendCompleted("request-1", response);

		assertThat(emitter.eventNames()).containsExactly(
			"status",
			"thought_summary",
			"content_delta",
			null,
			"ui_action",
			"completed"
		);
		assertThat(emitter.payload(0)).isEqualTo(Map.of("stage", "PLANNING"));
		assertThat(emitter.payload(1)).isEqualTo(Map.of("text", "계획 중"));
		assertThat(emitter.payload(2)).isEqualTo(Map.of("text", "답변"));
		assertThat(emitter.raw(3)).contains(":heartbeat");
		assertThat(emitter.payload(4)).isEqualTo(Map.of("action", action));
		assertThat(emitter.payload(5)).isEqualTo(Map.of(
			"requestId", "request-1",
			"result", response
		));
		assertThat(emitter.rawEvents())
			.noneMatch(value -> value.contains("statePatch")
				|| value.contains("actionsExecuted")
				|| value.contains("memoryCandidates")
				|| value.startsWith("id:"));
	}

	@Test
	void readyIsSentBeforeTheFirstHeartbeatWithUtcConnectedAt() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		AtomicLong clock = new AtomicLong();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter,
			clock::get
		);
		Instant connectedAt = Instant.parse("2026-09-20T01:02:03Z");

		connection.sendReady(connectedAt);
		clock.set(java.time.Duration.ofSeconds(10).toNanos());
		connection.sendHeartbeatIfIdle(
			SessionStreamService.HEARTBEAT_INTERVAL.toNanos()
		);

		assertThat(emitter.eventNames()).containsExactly("ready", null);
		assertThat(emitter.payload(0)).isEqualTo(Map.of(
			"sessionId", 100L,
			"connectedAt", "2026-09-20T01:02:03Z"
		));
		assertThat(emitter.raw(1)).contains(":heartbeat");
	}

	@Test
	void readyWriteFailureClosesAndCleansUpConnection() {
		AtomicInteger cleanupCount = new AtomicInteger();
		SseEmitter failingEmitter = new SseEmitter(0L) {
			@Override
			public synchronized void send(SseEventBuilder builder)
				throws IOException {
				throw new IOException("downstream closed");
			}
		};
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			cleanupCount::incrementAndGet,
			failingEmitter
		);

		assertThatThrownBy(() -> connection.sendReady(Instant.now()))
			.isInstanceOfSatisfying(
				io.edupilot.ai.AiClientException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(
						io.edupilot.global.error.ErrorCode
							.AI_STREAM_INTERRUPTED
					)
			);
		assertThat(connection.isClosed()).isTrue();
		assertThat(cleanupCount).hasValue(1);
	}

	@Test
	void diagnosisActionOmitsBinaryDecisionFields() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.sendUiAction(
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);

		JsonNode payload = objectMapper.valueToTree(emitter.payload(0));
		JsonNode action = payload.get("action");
		assertThat(action.get("type").textValue())
			.isEqualTo("DIAGNOSIS_QUESTION");
		assertThat(action.get("content").textValue()).isEqualTo("진단 질문");
		assertThat(action.get("diagnosisId").longValue()).isEqualTo(30L);
		assertThat(action.get("yesEvent")).isNull();
		assertThat(action.get("noEvent")).isNull();
	}

	@Test
	void noteDraftAppearsOnlyInCompletedEvent() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		TurnResponse response = new TurnResponse(
			"turn-note",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(3, PageStatus.EXPLAINED, null),
			new NoteDraft("복습 노트", "## 핵심\n내용")
		);

		connection.sendCompleted("request-note", response);

		assertThat(emitter.eventNames()).containsExactly("completed");
		JsonNode payload = objectMapper.valueToTree(emitter.payload(0));
		assertThat(payload.get("requestId").textValue())
			.isEqualTo("request-note");
		assertThat(payload.get("result").get("noteDraft").get("title")
			.textValue()).isEqualTo("복습 노트");
		assertThat(emitter.eventNames()).doesNotContain("content_delta");
	}

	@Test
	void completedCanOnlyBeSentOnce() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		TurnResponse response = response(UiAction.quizProposal());

		connection.sendCompleted("request-1", response);

		assertThatThrownBy(() ->
			connection.sendCompleted("request-1", response))
			.isInstanceOf(io.edupilot.ai.AiClientException.class);
		assertThat(emitter.eventNames()).containsExactly("completed");
	}

	@Test
	void relayWriteFailureCancelsUpstream() {
		SseEmitter failingEmitter = new SseEmitter(0L) {
			@Override
			public synchronized void send(SseEventBuilder builder)
				throws IOException {
				throw new IOException("downstream closed");
			}
		};
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			failingEmitter
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);

		assertThatThrownBy(() ->
			connection.send(TurnStreamEvent.contentDelta("partial")))
			.isInstanceOfSatisfying(
				io.edupilot.ai.AiClientException.class,
				exception -> assertThat(exception.errorCode())
					.isEqualTo(
						io.edupilot.global.error.ErrorCode
							.AI_STREAM_INTERRUPTED
					)
			);
		assertThat(cancellation.isCancelled()).isTrue();
		assertThat(cancellation.isUserCancelled()).isFalse();
	}

	@Test
	void userCancellationRequiresRunningTurnAndKeepsItsOrigin() {
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			}
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();

		assertThat(connection.cancelTurn()).isFalse();
		assertThat(connection.begin(cancellation)).isTrue();
		assertThat(connection.cancelTurn()).isTrue();
		assertThat(cancellation.isCancelled()).isTrue();
		assertThat(cancellation.isUserCancelled()).isTrue();
	}

	@Test
	void heartbeatIsCommentOnlyAfterTenSecondsOfInactivity() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		AtomicLong clock = new AtomicLong();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter,
			clock::get
		);

		clock.set(java.time.Duration.ofSeconds(9).toNanos());
		connection.sendHeartbeatIfIdle(
			SessionStreamService.HEARTBEAT_INTERVAL.toNanos()
		);
		assertThat(emitter.rawEvents()).isEmpty();

		clock.set(java.time.Duration.ofSeconds(10).toNanos());
		connection.sendHeartbeatIfIdle(
			SessionStreamService.HEARTBEAT_INTERVAL.toNanos()
		);
		assertThat(emitter.rawEvents()).singleElement()
			.satisfies(value -> {
				assertThat(value).contains(":heartbeat");
				assertThat(value).doesNotContain("event:", "data:");
			});
	}

	@Test
	void closeWinningBeforeBeginCannotLoseAnUpstreamCancellationReference() throws Exception {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		Object stateLock = ReflectionTestUtils.getField(connection, "lifecycleLock");
		AiStreamCancellation cancellation = new AiStreamCancellation();
		CountDownLatch beginRequested = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<Boolean> begin;
			synchronized (stateLock) {
				begin = executor.submit(() -> {
					beginRequested.countDown();
					return connection.begin(cancellation);
				});
				assertThat(beginRequested.await(3, TimeUnit.SECONDS)).isTrue();
				emitter.completion.run();
			}
			assertThat(begin.get(3, TimeUnit.SECONDS)).isFalse();
			assertThat(connection.isRunning()).isFalse();
			assertThat(cleanup).hasValue(1);
			assertThat(ReflectionTestUtils.getField(connection, "cancellation")).isNull();
		}
	}

	@Test
	void beginWinningBeforeCloseCancelsExactlyItsAttachedUpstream() throws Exception {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		Object stateLock = ReflectionTestUtils.getField(connection, "lifecycleLock");
		AiStreamCancellation cancellation = new AiStreamCancellation();
		CountDownLatch closeRequested = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> close;
			synchronized (stateLock) {
				close = executor.submit(() -> {
					closeRequested.countDown();
					emitter.completion.run();
				});
				assertThat(closeRequested.await(3, TimeUnit.SECONDS)).isTrue();
				assertThat(connection.begin(cancellation)).isTrue();
			}
			close.get(3, TimeUnit.SECONDS);
			assertThat(cancellation.isCancelled()).isTrue();
			assertThat(cancellation.isUserCancelled()).isFalse();
			assertThat(connection.isRunning()).isFalse();
			assertThat(cleanup).hasValue(1);
			assertThat(ReflectionTestUtils.getField(connection, "cancellation")).isNull();
		}
	}

	@ParameterizedTest
	@CsvSource({
		"ready,READY_SEND_FAILED", "status,STATUS_SEND_FAILED",
		"content_delta,CONTENT_SEND_FAILED", "heartbeat,HEARTBEAT_SEND_FAILED"
	})
	void sendFailuresCleanResourcesOnceAndKeepTheirReason(String event, String reason) {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		emitter.failingEvent = event;
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
		connection.heartbeatTask(heartbeat);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		if (!event.equals("ready")) {
			connection.sendReady(Instant.now());
			connection.begin(cancellation);
		}
		assertThatThrownBy(() -> {
			switch (event) {
				case "ready" -> connection.sendReady(Instant.now());
				case "status" -> connection.send(TurnStreamEvent.status("PLANNING"));
				case "content_delta" -> connection.send(TurnStreamEvent.contentDelta("private answer"));
				default -> connection.send(TurnStreamEvent.heartbeat());
			}
		}).isInstanceOf(io.edupilot.ai.AiClientException.class);
		emitter.completion.run();
		emitter.timeout.run();
		emitter.error.accept(new IOException("late private exception"));
		assertThat(connection.closeReason().name()).isEqualTo(reason);
		assertThat(cleanup).hasValue(1);
		assertThat(cancellation.isCancelled()).isEqualTo(!event.equals("ready"));
		verify(heartbeat, times(1)).cancel(false);
		assertThat(ReflectionTestUtils.getField(connection, "heartbeatTask")).isNull();
		ScheduledFuture<?> lateTask = mock(ScheduledFuture.class);
		connection.heartbeatTask(lateTask);
		verify(lateTask).cancel(false);
		assertThat(connection.begin(new AiStreamCancellation())).isFalse();
	}

	@ParameterizedTest
	@CsvSource({"TIMEOUT,EMITTER_TIMEOUT", "ERROR,EMITTER_ERROR", "COMPLETION,EMITTER_COMPLETION"})
	void emitterCallbacksCancelOnlyTheBoundTurnAndDoNotGuessNetworkCause(String callback, String reason) {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);
		switch (callback) {
			case "TIMEOUT" -> emitter.timeout.run();
			case "ERROR" -> emitter.error.accept(new IOException("private exception body"));
			default -> emitter.completion.run();
		}
		emitter.completion.run();
		assertThat(connection.closeReason().name()).isEqualTo(reason);
		assertThat(cancellation.isCancelled()).isTrue();
		assertThat(cleanup).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void terminalReasonSurvivesSynchronousAndRepeatedCallbacks(boolean error) {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation);
		if (error) {
			connection.sendError(new SessionStreamError("AI_RESPONSE_INVALID", "VALIDATION", "오류", false, "trace"));
		} else {
			connection.sendCompleted("request-1", response(UiAction.quizProposal()));
		}
		emitter.completion.run();
		emitter.timeout.run();
		emitter.error.accept(new IOException("late error"));
		assertThat(connection.closeReason()).isEqualTo(error
			? SessionStreamConnection.CloseReason.APPLICATION_ERROR
			: SessionStreamConnection.CloseReason.COMPLETED);
		assertThat(cleanup).hasValue(1);
		assertThat(cancellation.isCancelled()).isFalse();
	}

	@Test
	void terminalDeliveryFailureIsSeparateFromTheFirstTerminationReason() {
		ControllableSseEmitter emitter = new ControllableSseEmitter();
		emitter.failingEvent = "completed";
		AtomicInteger cleanup = new AtomicInteger();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L, 100L, cleanup::incrementAndGet, emitter
		);
		AiStreamCancellation cancellation = new AiStreamCancellation();
		connection.begin(cancellation, "request-terminal", "turn-trace");
		Logger logger = (Logger)LoggerFactory.getLogger(SessionStreamConnection.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> connection.sendCompleted("request-terminal", response(UiAction.quizProposal())))
				.isInstanceOf(io.edupilot.ai.AiClientException.class);
			emitter.error.accept(new IOException("late callback"));
			assertThat(connection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.COMPLETED);
			assertThat(cleanup).hasValue(1);
			assertThat(cancellation.isCancelled()).isFalse();
			assertThat(appender.list.stream()
				.filter(event -> event.getMessage().equals("Session SSE event delivery failed")))
				.singleElement().satisfies(event -> {
					Map<String, Object> fields = new LinkedHashMap<>();
					event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
					assertThat(fields).containsEntry("event", "completed")
						.containsEntry("deliveryResult", "FAILED")
						.containsEntry("requestId", "request-terminal");
				});
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void callbackLogsUseStoredCorrelationWithoutMdcOrPrivatePayloads() {
		Logger logger = (Logger)LoggerFactory.getLogger(SessionStreamConnection.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "connect-trace");
			ControllableSseEmitter emitter = new ControllableSseEmitter();
			SessionStreamConnection connection = new SessionStreamConnection(1L, 100L, () -> {}, emitter);
			connection.sendReady(Instant.now());
			connection.begin(new AiStreamCancellation(), "request-log", "turn-trace");
			connection.aiAttempt("attempt-turn", 2);
			connection.send(TurnStreamEvent.contentDelta("private answer must not be logged"));
			MDC.clear();
			emitter.error.accept(new IOException("private exception body must not be logged"));
			emitter.completion.run();
			var closed = appender.list.stream()
				.filter(event -> event.getMessage().equals("Session SSE connection closed")).toList();
			assertThat(closed).hasSize(1);
			Map<String, Object> fields = new LinkedHashMap<>();
			closed.getFirst().getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
			assertThat(fields).containsEntry("connectionId", connection.connectionId())
				.containsEntry("connectionTraceId", "connect-trace")
				.containsEntry("turnTraceId", "turn-trace")
				.containsEntry("requestId", "request-log")
				.containsEntry("turnId", "attempt-turn")
				.containsEntry("attempt", 2)
				.containsEntry("reason", SessionStreamConnection.CloseReason.EMITTER_ERROR)
				.containsEntry("upstreamCancelled", true);
			assertThat(fields.get("occurredAt")).isInstanceOf(Instant.class);
			assertThat(appender.list.toString() + appender.list.stream()
				.map(ILoggingEvent::getKeyValuePairs).toList())
				.doesNotContain("private answer", "private exception body");
		} finally {
			MDC.clear();
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void errorIsTheOnlyTerminalEventAndCarriesPublicSchema() {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SessionStreamConnection connection = new SessionStreamConnection(
			1L,
			100L,
			() -> {
			},
			emitter
		);
		connection.begin(new AiStreamCancellation());
		connection.send(TurnStreamEvent.status("PLANNING"));
		connection.sendError(new SessionStreamError(
			"AI_SERVICE_TIMEOUT",
			"TIMEOUT",
			"AI 서비스 응답 시간이 초과되었습니다.",
			true,
			"trace-1"
		));

		assertThat(emitter.eventNames()).containsExactly("status", "error");
		JsonNode error = objectMapper.valueToTree(emitter.payload(1));
		assertThat(error.get("code").textValue())
			.isEqualTo("AI_SERVICE_TIMEOUT");
		assertThat(error.get("category").textValue()).isEqualTo("TIMEOUT");
		assertThat(error.get("retryable").booleanValue()).isTrue();
		assertThat(error.get("traceId").textValue()).isEqualTo("trace-1");
		assertThat(emitter.eventNames()).doesNotContain("completed");
	}

	private TurnResponse response(UiAction action) {
		return new TurnResponse(
			"turn-123",
			100L,
			List.of(new MessageResponse(
				501L,
				SenderType.AI,
				MessageType.EXPLANATION,
				"답변",
				3,
				ChatMessageStatus.COMPLETED,
				Instant.parse("2026-07-28T09:00:00Z")
			)),
			List.of(action),
			new TurnStateResponse(3, PageStatus.EXPLAINED, null)
		);
	}

	private static final class CapturingSseEmitter extends SseEmitter {

		private final List<List<Object>> events = new ArrayList<>();

		private CapturingSseEmitter() {
			super(0L);
		}

		@Override
		public synchronized void send(SseEventBuilder builder)
			throws IOException {
			events.add(builder.build().stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.toList());
		}

		List<String> eventNames() {
			return events.stream().map(this::eventName).toList();
		}

		Object payload(int index) {
			return events.get(index).stream()
				.filter(value -> !(value instanceof String))
				.findFirst()
				.orElse(null);
		}

		String raw(int index) {
			return events.get(index).stream()
				.map(String::valueOf)
				.reduce("", String::concat);
		}

		List<String> rawEvents() {
			return events.stream()
				.map(values -> values.stream()
					.map(String::valueOf)
					.reduce("", String::concat))
				.toList();
		}

		private String eventName(List<Object> values) {
			return values.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.filter(value -> value.startsWith("event:"))
				.map(value -> value.substring("event:".length()))
				.map(value -> value.lines().findFirst().orElse("").trim())
				.findFirst()
				.orElse(null);
		}
	}
}
