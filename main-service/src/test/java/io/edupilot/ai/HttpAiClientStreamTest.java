package io.edupilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.TurnResponse;
import io.edupilot.global.error.ErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class HttpAiClientStreamTest {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void parsesAllowedNdjsonEventsAndReturnsCompletedResult()
		throws Exception {
		server.enqueue(ndjson("""
			{"type":"status","stage":"PLANNING"}
			{"type":"thought_summary","text":"학습 계획을 세우는 중입니다"}
			{"type":"content_delta","text":"편차는 "}
			{"type":"heartbeat"}
			{"type":"content_delta","text":"평균과 관측값의 차이입니다."}
			%s
			""".formatted(completed(
				"turn-stream",
				"편차는 평균과 관측값의 차이입니다."
			))));
		List<TurnStreamEvent> events = new ArrayList<>();

		var response = client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream"),
				events::add,
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			);

		assertThat(response.turnId()).isEqualTo("turn-stream");
		assertThat(response.turnGoal())
			.isEqualTo("Answer the learner's question using the current page");
		assertThat(events)
			.extracting(TurnStreamEvent::type)
			.containsExactly(
				TurnStreamEvent.Type.STATUS,
				TurnStreamEvent.Type.THOUGHT_SUMMARY,
				TurnStreamEvent.Type.CONTENT_DELTA,
				TurnStreamEvent.Type.HEARTBEAT,
				TurnStreamEvent.Type.CONTENT_DELTA
			);
		RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(recorded).isNotNull();
		assertThat(recorded.getHeader("Accept"))
			.isEqualTo("application/x-ndjson");
	}

	@Test
	void quizTurnCompletesWithoutContentDeltaAndKeepsTopLevelQuiz() {
		server.enqueue(ndjson(completedQuiz("turn-quiz")));
		List<TurnStreamEvent> events = new ArrayList<>();

		var response = client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-quiz", "QUIZ_TYPE_SELECTED"),
				events::add,
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			);

		assertThat(events).isEmpty();
		assertThat(response.quiz()).isNotNull();
		assertThat(response.quiz().quizType()).isEqualTo("MCQ");
		assertThat(response.quiz().questions()).hasSize(5);
	}

	@Test
	void noteTurnsCompleteWithoutContentDelta() {
		for (String eventType : List.of("NOTE_REQUESTED", "USER_QUESTION")) {
			server.enqueue(ndjson(completedNote("turn-note")));

			var response = client(Duration.ofSeconds(1))
				.executeTurnStream(
					request("turn-note", eventType),
					event -> {
					},
					new AiStreamCancellation(),
					Duration.ofSeconds(2)
				);

			assertThat(response.turnGoal())
				.isEqualTo("Summarize the lesson as a study note");
			assertThat(response.noteDraft()).isNotNull();
		}
	}

	@Test
	void diagnosisCompletesWithoutContentDelta() {
		server.enqueue(ndjson(completedDiagnosis("turn-diagnosis")));

		var response = client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-diagnosis", "DIAGNOSIS_ANSWER_SUBMITTED"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			);

		assertThat(response.turnGoal())
			.isEqualTo("Repair the learner's misconception");
		assertThat(response.messages()).singleElement().satisfies(message ->
			assertThat(message.get("messageType")).isEqualTo("REPAIR")
		);
	}

	@Test
	void explanationAcceptsFreeTextGoalAndUiActionsWithMatchingDelta() {
		server.enqueue(ndjson("""
			{"type":"content_delta","text":"페이지 설명"}
			%s
			""".formatted(completedExplanation(
			"turn-explain",
			"페이지 설명"
		))));

		var response = client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-explain", "EXPLAIN_CURRENT_PAGE"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			);

		assertThat(response.turnGoal()).isEqualTo(
			"Explain page 17 on high-dimensional gradient descent geometry"
		);
		assertThat(response.uiActions()).hasSize(1);
	}

	@Test
	void rejectsMissingDeltaForExplanationAndQuestion() {
		assertInvalid(
			completedExplanation("turn-stream", "페이지 설명"),
			"EXPLAIN_CURRENT_PAGE"
		);
		assertInvalid(completed("turn-stream", "질문 답변"));
	}

	@Test
	void rejectsDeltaForCompletedOnlyNoteTurn() {
		assertInvalid("""
			{"type":"content_delta","text":"노트"}
			%s
			""".formatted(completedNote("turn-stream")), "NOTE_REQUESTED");
	}

	@Test
	void rejectsIncompleteDirectNoteVariants() {
		assertInvalid(completedNoteVariant(
			"turn-stream",
			"[{\"messageType\":\"SYSTEM\",\"content\":\"노트\"}]",
			"{}",
			"null"
		));
		assertInvalid(completedNoteVariant(
			"turn-stream",
			"[{\"messageType\":\"SYSTEM\",\"content\":\"노트\"}]",
			"{\"pageStatus\":\"EXPLAINED\"}",
			"{\"title\":\"복습 노트\",\"content\":\"핵심 내용\"}"
		));
		assertInvalid(completedNoteVariant(
			"turn-stream",
			"[{\"messageType\":\"SYSTEM\",\"content\":\"노트 1\"},{\"messageType\":\"SYSTEM\",\"content\":\"노트 2\"}]",
			"{}",
			"{\"title\":\"복습 노트\",\"content\":\"핵심 내용\"}"
		));
	}

	@Test
	void rejectsQuizShapeWithoutQuizArtifact() {
		assertInvalid(completedQuizWithoutQuiz("turn-stream"), "QUIZ_TYPE_SELECTED");
	}

	@Test
	void logsTheSpecificShapeRuleThatRejectedCompletion() {
		assertThat(invalidValidationRule(
			completedExplanation("turn-stream", "페이지 설명"),
			"EXPLAIN_CURRENT_PAGE"
		)).isEqualTo("explain-empty-delta");
		assertThat(invalidValidationRule(
			completed("turn-stream", "질문 답변"),
			"USER_QUESTION"
		)).isEqualTo("note-shape-missing-draft");
		assertThat(invalidValidationRule(
			completedQuizWithoutQuiz("turn-stream"),
			"QUIZ_TYPE_SELECTED"
		)).isEqualTo("quiz-shape-missing-quiz");
	}

	@Test
	void rejectsUnknownMalformedAndPostTerminalEvents() {
		assertInvalid("""
			{"type":"unknown"}
			""");
		assertInvalid("""
			not-json
			""");
		assertInvalid("""
			{"type":"status","stage":"PLANNING","extra":true}
			""");
		assertInvalid("""
			%s
			{"type":"heartbeat"}
			""".formatted(completed("turn-stream", "")));
	}

	@Test
	void rejectsContentDeltaMismatch() {
		assertInvalid("""
			{"type":"content_delta","text":"임시 내용"}
			%s
			""".formatted(completed("turn-stream", "최종 내용")));
	}

	@Test
	void mapsEofBeforeTerminalToStreamInterrupted() {
		server.enqueue(ndjson("""
			{"type":"status","stage":"PLANNING"}
			"""));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_STREAM_INTERRUPTED)
			);
	}

	@Test
	void mapsDirectSocketTimeoutIOExceptionToServiceTimeout() {
		assertStreamReadFailure(
			new SocketTimeoutException("read timed out"),
			ErrorCode.AI_SERVICE_TIMEOUT
		);
	}

	@Test
	void mapsWrappedSocketTimeoutIOExceptionToServiceTimeout() {
		assertStreamReadFailure(
			new IOException(
				"wrapped",
				new SocketTimeoutException("read timed out")
			),
			ErrorCode.AI_SERVICE_TIMEOUT
		);
	}

	@Test
	void mapsGeneralIOExceptionToStreamInterrupted() {
		assertStreamReadFailure(
			new IOException("connection closed"),
			ErrorCode.AI_STREAM_INTERRUPTED
		);
	}

	@Test
	void mapsTerminalTimeoutErrorWithoutCompleted() {
		server.enqueue(ndjson("""
			{"type":"error","code":"AI_SERVICE_TIMEOUT","category":"TIMEOUT","message":"safe","retryable":true}
			"""));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT);
				assertThat(exception.retryable()).isTrue();
			});
	}

	@Test
	void idleTimeoutClosesSilentUpstream() {
		server.enqueue(ndjson(completed("turn-stream", ""))
			.setBodyDelay(300, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(50))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
	}

	@Test
	void firstEventTimeoutIncludesResponseHeaderWait() {
		server.enqueue(ndjson(completed("turn-stream", ""))
			.setHeadersDelay(300, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(50))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
	}

	@Test
	void totalTimeoutWinsEvenWhenIdleLimitIsLonger() {
		server.enqueue(ndjson(completed("turn-stream", ""))
			.setBodyDelay(300, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofMillis(50)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
	}

	@Test
	void heartbeatStreamCannotExtendTotalTimeout() {
		String heartbeat = "{\"type\":\"heartbeat\"}\n";
		server.enqueue(ndjson(heartbeat.repeat(20))
			.throttleBody(
				heartbeat.getBytes(StandardCharsets.UTF_8).length,
				100,
				TimeUnit.MILLISECONDS
			));
		assertThatThrownBy(() -> client(Duration.ofSeconds(2))
			.executeTurnStream(
				request("turn-stream"),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(1)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
	}

	@Test
	void downstreamCancellationClosesUpstream() {
		String heartbeat = "{\"type\":\"heartbeat\"}\n";
		server.enqueue(ndjson(heartbeat.repeat(20))
			.throttleBody(
				heartbeat.getBytes(StandardCharsets.UTF_8).length,
				20,
				TimeUnit.MILLISECONDS
			));
		AiStreamCancellation cancellation = new AiStreamCancellation();

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream"),
				event -> cancellation.cancel(),
				cancellation,
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_STREAM_INTERRUPTED)
			);
		assertThat(cancellation.isCancelled()).isTrue();
	}

	private void assertInvalid(String body) {
		assertInvalid(body, "USER_QUESTION");
	}

	private void assertInvalid(String body, String eventType) {
		server.enqueue(ndjson(body));
		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurnStream(
				request("turn-stream", eventType),
				event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
	}

	private String invalidValidationRule(String body, String eventType) {
		Logger logger = (Logger) LoggerFactory.getLogger(HttpAiClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			assertInvalid(body, eventType);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		return appender.list.stream()
			.filter(event -> event.getFormattedMessage().equals(
				"AI stream completion validation failed"
			))
			.flatMap(event -> event.getKeyValuePairs().stream())
			.filter(pair -> "validationRule".equals(pair.key))
			.map(pair -> String.valueOf(pair.value))
			.findFirst()
			.orElseThrow();
	}

	private void assertStreamReadFailure(
		IOException failure,
		ErrorCode expectedError
	) {
		assertThatThrownBy(() -> readTurnStream(failingStream(failure)))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expectedError)
			);
	}

	private TurnResponse readTurnStream(InputStream body) throws Throwable {
		Method method = HttpAiClient.class.getDeclaredMethod(
			"readTurnStream",
			HttpStatusCode.class,
			MediaType.class,
			InputStream.class,
			TurnRequest.class,
			Consumer.class,
			AiStreamCancellation.class,
			Duration.class
		);
		method.setAccessible(true);
		try {
			return (TurnResponse) method.invoke(
				client(Duration.ofSeconds(1)),
				HttpStatusCode.valueOf(200),
				MediaType.parseMediaType("application/x-ndjson"),
				body,
				request("turn-stream"),
				(Consumer<TurnStreamEvent>) event -> {
				},
				new AiStreamCancellation(),
				Duration.ofSeconds(2)
			);
		} catch (InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	private InputStream failingStream(IOException failure) {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				throw failure;
			}
		};
	}

	private HttpAiClient client(Duration idleTimeout) {
		return new HttpAiClient(new AiClientProperties(
			server.url("/").uri(),
			"stream-test-token",
			Duration.ofMillis(300),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(2),
			idleTimeout,
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1),
			"/health"
		));
	}

	private TurnRequest request(String turnId) {
		return request(turnId, "USER_QUESTION");
	}

	private TurnRequest request(String turnId, String eventType) {
		return new TurnRequest(
			"1.0",
			turnId,
			Map.of("sessionId", 100L),
			Map.of("eventType", eventType, "payload", Map.of()),
			Map.of()
		);
	}

	private MockResponse ndjson(String body) {
		return new MockResponse()
			.setResponseCode(200)
			.setHeader("Content-Type", "application/x-ndjson")
			.setBody(body);
	}

	private String completed(String turnId, String content) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Answer the learner's question using the current page","actionsExecuted":[],"messages":[{"messageType":"QA","content":"%s"}],"statePatch":{},"uiActions":[],"memoryCandidates":[]}}
			""".formatted(turnId, content).strip();
	}

	private String completedExplanation(String turnId, String content) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Explain page 17 on high-dimensional gradient descent geometry","actionsExecuted":[],"messages":[{"messageType":"EXPLANATION","content":"%s"}],"statePatch":{"pageStatus":"EXPLAINED"},"uiActions":[{"type":"BINARY_DECISION","content":"퀴즈를 진행할까요?","yesEvent":"SHOW_QUIZ_TYPE_SELECT","noEvent":"WAIT"}],"memoryCandidates":[]}}
			""".formatted(turnId, content).strip();
	}

	private String completedNote(String turnId) {
		return completedNoteVariant(
			turnId,
			"[{\"messageType\":\"SYSTEM\",\"content\":\"노트 초안을 만들었습니다.\"}]",
			"{}",
			"{\"title\":\"복습 노트\",\"content\":\"핵심 내용\"}"
		);
	}

	private String completedNoteVariant(
		String turnId,
		String messages,
		String statePatch,
		String noteDraft
	) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Summarize the lesson as a study note","actionsExecuted":[],"messages":%s,"statePatch":%s,"uiActions":[],"memoryCandidates":[],"noteDraft":%s}}
			""".formatted(turnId, messages, statePatch, noteDraft).strip();
	}

	private String completedDiagnosis(String turnId) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Repair the learner's misconception","actionsExecuted":[],"messages":[{"messageType":"REPAIR","content":"교정 설명"}],"statePatch":{"pageStatus":"REPAIR_COMPLETED","pendingDiagnosis":null},"uiActions":[],"memoryCandidates":[]}}
			""".formatted(turnId).strip();
	}

	private String completedQuiz(String turnId) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Generate a five-question checkpoint quiz","actionsExecuted":[],"messages":[],"statePatch":{"pageStatus":"QUIZ_READY"},"uiActions":[],"quiz":{"schemaVersion":"1.0","generationId":"generation-1","quizType":"MCQ","coverage":{"startPage":2,"endPage":4},"title":"퀴즈","questionCount":5,"questions":[{"questionId":"q1","questionText":"문항 1","points":10,"choices":[{"choiceId":"a","text":"A"},{"choiceId":"b","text":"B"}],"answerChoiceId":"a","explanation":"해설"},{"questionId":"q2","questionText":"문항 2","points":10,"choices":[{"choiceId":"a","text":"A"},{"choiceId":"b","text":"B"}],"answerChoiceId":"a","explanation":"해설"},{"questionId":"q3","questionText":"문항 3","points":10,"choices":[{"choiceId":"a","text":"A"},{"choiceId":"b","text":"B"}],"answerChoiceId":"a","explanation":"해설"},{"questionId":"q4","questionText":"문항 4","points":10,"choices":[{"choiceId":"a","text":"A"},{"choiceId":"b","text":"B"}],"answerChoiceId":"a","explanation":"해설"},{"questionId":"q5","questionText":"문항 5","points":10,"choices":[{"choiceId":"a","text":"A"},{"choiceId":"b","text":"B"}],"answerChoiceId":"a","explanation":"해설"}]},"memoryCandidates":[]}}
			""".formatted(turnId).strip();
	}

	private String completedQuizWithoutQuiz(String turnId) {
		return """
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"Generate a five-question checkpoint quiz","actionsExecuted":[],"messages":[],"statePatch":{"pageStatus":"QUIZ_READY"},"uiActions":[],"memoryCandidates":[]}}
			""".formatted(turnId).strip();
	}
}
