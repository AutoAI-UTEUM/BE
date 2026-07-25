package io.edupilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class HttpAiClientContractTest {

	private static final String INTERNAL_TOKEN = "contract-test-token";
	private static final String TRACE_ID = "contract-test-trace";

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, TRACE_ID);
	}

	@AfterEach
	void tearDown() throws IOException {
		MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
		server.shutdown();
	}

	@Test
	void turnResponseUsesMinimumContractAndPropagatesInternalHeaders() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnId": "turn-123",
			  "turnGoal": "ANSWER_USER_QUESTION",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			"""));

		var response = client(Duration.ofSeconds(1))
			.executeTurn(turnRequest("turn-123"));

		assertThat(response.turnId()).isEqualTo("turn-123");
		assertThat(response.turnGoal()).isEqualTo("ANSWER_USER_QUESTION");

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/internal/ai/turn");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void extractSendsPdfMultipartAndValidatesResponse() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 2,
			  "pages": [
			    {"pageNumber": 1, "text": "first"},
			    {"pageNumber": 2, "text": "second"}
			  ]
			}
			"""));

		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};
		var response = client(Duration.ofSeconds(1)).extract(pdf);

		assertThat(response.pageCount()).isEqualTo(2);
		assertThat(response.pages()).extracting("pageNumber").containsExactly(1, 2);
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo("/internal/ai/extract");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
		assertThat(request.getBody().readUtf8())
			.contains("name=\"file\"")
			.contains("filename=\"material.pdf\"")
			.contains("%PDF-test");
	}

	@Test
	void extractRejectsNonContiguousPageNumbers() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 1,
			  "pages": [{"pageNumber": 2, "text": "wrong"}]
			}
			"""));

		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).extract(pdf))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
	}

	@Test
	void delayedExtractResponseUsesExtractTimeoutWithoutRetry() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 1,
			  "pages": [{"pageNumber": 1, "text": "page"}]
			}
			""").setBodyDelay(500, TimeUnit.MILLISECONDS));
		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};

		assertThatThrownBy(() -> client(Duration.ofMillis(100)).extract(pdf))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void healthResponseIsParsedFromConfiguredPath() throws Exception {
		server.enqueue(jsonResponse(200, """
			{"status": "UP"}
			"""));

		var response = client(Duration.ofSeconds(1)).health();

		assertThat(response.status()).isEqualTo("UP");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("GET");
		assertThat(request.getPath()).isEqualTo("/health");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
	}

	@Test
	void unavailableServerMapsToServiceUnavailableWithoutRetry() throws IOException {
		int unusedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}
		HttpAiClient client = new HttpAiClient(properties(
			URI.create("http://127.0.0.1:" + unusedPort),
			Duration.ofMillis(200)
		));

		assertThatThrownBy(client::health)
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
	}

	@Test
	void delayedResponseMapsToServiceTimeoutWithoutRetry() {
		server.enqueue(jsonResponse(200, """
			{"status": "UP"}
			""").setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(100)).health())
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void malformedSuccessBodyMapsToInvalidResponse() {
		server.enqueue(new MockResponse()
			.setResponseCode(200)
			.setHeader("Content-Type", "text/html")
			.setBody("<html>not-json</html>"));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).health())
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void missingRequiredTurnFieldMapsToInvalidResponse() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnGoal": "ANSWER_USER_QUESTION",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			"""));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void schemaErrorCategoryMapsToInvalidResponse() {
		server.enqueue(jsonResponse(422, errorBody("SCHEMA", false)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void authenticationErrorMapsToInternalErrorWithoutLeakingRemoteMessage() {
		server.enqueue(jsonResponse(401, errorBody("AUTH", false)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
				assertThat(exception.clientMessage())
					.isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.message())
					.doesNotContain("token mismatch");
			});
	}

	private HttpAiClient client(Duration readTimeout) {
		return new HttpAiClient(properties(server.url("/").uri(), readTimeout));
	}

	private AiClientProperties properties(URI baseUrl, Duration readTimeout) {
		return new AiClientProperties(
			baseUrl,
			INTERNAL_TOKEN,
			Duration.ofMillis(300),
			readTimeout,
			readTimeout,
			"/health"
		);
	}

	private TurnRequest turnRequest(String turnId) {
		return new TurnRequest(
			"1.0",
			turnId,
			Map.of("sessionId", 100),
			Map.of("eventType", "USER_QUESTION", "payload", Map.of()),
			Map.of()
		);
	}

	private MockResponse jsonResponse(int status, String body) {
		return new MockResponse()
			.setResponseCode(status)
			.setHeader("Content-Type", "application/json")
			.setBody(body);
	}

	private String errorBody(String category, boolean retryable) {
		return """
			{
			  "schemaVersion": "1.0",
			  "error": {
			    "code": "REMOTE_ERROR",
			    "category": "%s",
			    "message": "token mismatch: secret-value",
			    "retryable": %s
			  },
			  "traceId": "ai-trace"
			}
			""".formatted(category, retryable);
	}
}
