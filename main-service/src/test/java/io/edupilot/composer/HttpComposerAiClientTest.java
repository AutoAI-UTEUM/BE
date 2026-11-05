package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.edupilot.ai.AiClientProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import tools.jackson.databind.ObjectMapper;

class HttpComposerAiClientTest {
	MockWebServer server;
	HttpComposerAiClient client;
	final ComposerAiRequest request = new ComposerAiRequest("1.0", "message-4", "server-version",
		ComposerAiRequest.Activity.QA, List.of(new ComposerAiRequest.Message(ComposerAiRequest.Role.ASSISTANT, "Public answer")));
	@BeforeEach void setup() throws Exception {
		server = new MockWebServer(); server.start();
		var ai = mock(AiClientProperties.class);
		when(ai.baseUrl()).thenReturn(server.url("/").uri());
		when(ai.connectTimeout()).thenReturn(Duration.ofSeconds(1));
		when(ai.internalToken()).thenReturn("synthetic-internal-token");
		client = new HttpComposerAiClient(ai, ComposerAdmissionServiceTest.policy());
	}
	@AfterEach void close() throws Exception { client.close(); server.shutdown(); }

	@Test void usesExactInternalContractAndNeverSendsPrivateContext() throws Exception {
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(response()));
		var result = client.start(request).result().get(3, TimeUnit.SECONDS);
		assertThat(result.validFor(request)).isTrue();
		var sent = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(sent.getPath()).isEqualTo("/internal/ai/composer-prediction");
		assertThat(sent.getHeader("X-Internal-Token")).isEqualTo("synthetic-internal-token");
		String body = sent.getBody().readUtf8();
		var directory = java.nio.file.Path.of("build", "composer-wire");
		java.nio.file.Files.createDirectories(directory);
		java.nio.file.Files.writeString(directory.resolve("internal-request.json"), body);
		var json = new ObjectMapper().readTree(body);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "sourceTurnId", "contextVersion", "learningActivity", "messages");
		assertThat(json.get("messages").get(0).get("role").asString()).isEqualTo("ASSISTANT");
	}

	@ParameterizedTest @ValueSource(ints = {401, 422, 500, 503})
	void failuresAreSingleAttemptAndDoNotExposeBody(int status) throws Exception {
		server.enqueue(new MockResponse().setResponseCode(status).setBody("private-upstream-details"));
		assertThatThrownBy(() -> client.start(request).result().get(3, TimeUnit.SECONDS))
			.hasMessageNotContaining("private-upstream-details");
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test void malformedAndOversizedResponseAreRejected() {
		server.enqueue(new MockResponse().setBody("{not json"));
		assertThatThrownBy(() -> client.start(request).result().get(3, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
		server.enqueue(new MockResponse().setBody("x".repeat(20_000)));
		assertThatThrownBy(() -> client.start(request).result().get(3, TimeUnit.SECONDS)).hasMessageContaining("too large");
	}

	@Test void cancellationBeforeHeadersDoesNotWaitForUpstreamTimeout() throws Exception {
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		var call = client.start(request);
		assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
		call.cancel();
		assertThat(call.result().isDone()).isTrue();
		assertThatThrownBy(() -> call.result().join()).satisfies(error -> {
			Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
			assertThat(cause).isInstanceOf(java.util.concurrent.CancellationException.class);
		});
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test void cancellationWhileBodyIsStillStreamingClosesTheExchange() throws Exception {
		// MockWebServer also applies throttling to the request body. A chunk larger
		// than the request permits dispatch, while the response remains in flight.
		server.enqueue(new MockResponse().setBody(" ".repeat(12_000) + response()).throttleBody(1024, 50, TimeUnit.MILLISECONDS));
		var call = client.start(request);
		assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
		assertThat(call.result().isDone()).isFalse();
		call.cancel(); assertThat(call.result().isDone()).isTrue();
	}

	@Test void redirectsCannotForwardInternalTokenToAnotherEndpoint() {
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/different"));
		assertThatThrownBy(() -> client.start(request).result().get(3, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	static String response() {
		return """
			{"schemaVersion":"1.0","sourceTurnId":"message-4","contextVersion":"server-version",
			 "suggestion":"Could you give an example?","warnings":[],
			 "usage":{"model":"synthetic-model","inputTokens":12,"outputTokens":3,"costUsdTicks":5}}
			""";
	}
}
