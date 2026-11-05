package io.edupilot.composer;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import io.edupilot.ai.AiClientProperties;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

/** One attempt, bounded response, cancellable even while waiting for headers. */
@Component
@ConditionalOnProperty(name = "edupilot.composer.enabled", havingValue = "true")
public class HttpComposerAiClient implements ComposerAiClient {
	private final AiClientProperties ai;
	private final ComposerProperties policy;
	private final HttpClient http;
	private final ObjectMapper json = new ObjectMapper();

	public HttpComposerAiClient(AiClientProperties ai, ComposerProperties policy) {
		this.ai = ai;
		this.policy = policy;
		this.http = HttpClient.newBuilder().connectTimeout(ai.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	@Override
	public Call start(ComposerAiRequest input) {
		if (!policy.ready()) throw new IllegalStateException("Composer policy is not ready");
		var request = HttpRequest.newBuilder(ai.baseUrl().resolve("/internal/ai/composer-prediction"))
			.timeout(policy.timeout()).header("Content-Type", "application/json")
			.header("Accept", "application/json").header("X-Internal-Token", ai.internalToken())
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
		CompletableFuture<HttpResponse<byte[]>> transport = http.sendAsync(request, ignored -> new LimitedBody());
		CompletableFuture<ComposerAiResponse> result = transport.thenApply(response -> {
			if (response.statusCode() != 200) throw new IllegalStateException("Composer upstream unavailable");
			return json.readValue(response.body(), ComposerAiResponse.class);
		});
		return new Call() {
			public CompletableFuture<ComposerAiResponse> result() { return result; }
			public void cancel() {
				result.cancel(true);
				transport.cancel(true);
			}
		};
	}

	@PreDestroy
	public void close() { http.shutdownNow(); }

	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private static final int MAX_BYTES = 16_384;
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private Flow.Subscription subscription;
		public CompletionStage<byte[]> getBody() { return result; }
		public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_BYTES - bytes.size()) {
					subscription.cancel();
					result.completeExceptionally(new IllegalStateException("Composer response too large"));
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}
		public void onError(Throwable error) { result.completeExceptionally(error); }
		public void onComplete() { result.complete(bytes.toByteArray()); }
	}
}
