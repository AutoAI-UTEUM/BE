package io.edupilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.edupilot.ai.dto.TurnRequest;
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
	void heartbeatRefreshesIdleButCannotExtendTotalTimeout() {
		String heartbeat = "{\"type\":\"heartbeat\"}\n";
		server.enqueue(ndjson(heartbeat.repeat(20))
			.throttleBody(
				heartbeat.getBytes(StandardCharsets.UTF_8).length,
				20,
				TimeUnit.MILLISECONDS
			));
		AtomicInteger heartbeats = new AtomicInteger();

		assertThatThrownBy(() -> client(Duration.ofMillis(150))
			.executeTurnStream(
				request("turn-stream"),
				event -> heartbeats.incrementAndGet(),
				new AiStreamCancellation(),
				Duration.ofMillis(80)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(heartbeats.get()).isGreaterThan(1);
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
		server.enqueue(ndjson(body));
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
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
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
			"/health"
		));
	}

	private TurnRequest request(String turnId) {
		return new TurnRequest(
			"1.0",
			turnId,
			Map.of("sessionId", 100L),
			Map.of("eventType", "USER_QUESTION", "payload", Map.of()),
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
			{"type":"completed","result":{"schemaVersion":"1.0","turnId":"%s","turnGoal":"ANSWER_USER_QUESTION","actionsExecuted":[],"messages":[{"messageType":"QA","content":"%s"}],"statePatch":{},"uiActions":[],"memoryCandidates":[]}}
			""".formatted(turnId, content).strip();
	}
}
