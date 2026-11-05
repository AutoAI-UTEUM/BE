package io.edupilot.composer;

import java.util.concurrent.CompletableFuture;

public interface ComposerAiClient {
	Call start(ComposerAiRequest request);
	interface Call {
		CompletableFuture<ComposerAiResponse> result();
		void cancel();
	}
}
