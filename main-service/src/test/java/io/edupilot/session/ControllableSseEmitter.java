package io.edupilot.session;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** A deterministic downstream: no servlet container or real network required. */
final class ControllableSseEmitter extends SseEmitter {

	Runnable completion;
	Runnable timeout;
	Consumer<Throwable> error;
	volatile String failingEvent;
	String blockedEvent;
	final CountDownLatch sendEntered = new CountDownLatch(1);
	final CountDownLatch releaseSend = new CountDownLatch(1);

	ControllableSseEmitter() {
		super(0L);
	}

	@Override
	public synchronized void send(SseEventBuilder builder) throws IOException {
		String name = builder.build().stream()
			.map(part -> part.getData())
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(part -> part.startsWith("event:"))
			.map(part -> part.substring(6).lines().findFirst().orElse("").trim())
			.findFirst().orElse("heartbeat");
		if (name.equals(blockedEvent)) {
			sendEntered.countDown();
			try {
				if (!releaseSend.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out releasing SSE send");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException(exception);
			}
		}
		if (name.equals(failingEvent)) {
			throw new IOException("private exception body must not be logged");
		}
	}

	@Override
	public void onCompletion(Runnable callback) {
		completion = callback;
	}

	@Override
	public void onTimeout(Runnable callback) {
		timeout = callback;
	}

	@Override
	public void onError(Consumer<Throwable> callback) {
		error = callback;
	}

	@Override
	public void complete() {
		completion.run();
	}
}
