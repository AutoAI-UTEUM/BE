package io.edupilot.session;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.dto.TurnResponse;

final class SessionStreamConnection {

	private final Long userId;
	private final Long sessionId;
	private final SseEmitter emitter;
	private final Runnable cleanup;
	private final LongSupplier nanoTime;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong lastSentNanos;
	private volatile AiStreamCancellation cancellation;
	private volatile ScheduledFuture<?> heartbeatTask;

	SessionStreamConnection(Long userId, Long sessionId, Runnable cleanup) {
		this(
			userId,
			sessionId,
			cleanup,
			new SseEmitter(0L),
			System::nanoTime
		);
	}

	SessionStreamConnection(
		Long userId,
		Long sessionId,
		Runnable cleanup,
		SseEmitter emitter
	) {
		this(userId, sessionId, cleanup, emitter, System::nanoTime);
	}

	SessionStreamConnection(
		Long userId,
		Long sessionId,
		Runnable cleanup,
		SseEmitter emitter,
		LongSupplier nanoTime
	) {
		this.userId = userId;
		this.sessionId = sessionId;
		this.cleanup = cleanup;
		this.emitter = emitter;
		this.nanoTime = nanoTime;
		this.lastSentNanos = new AtomicLong(nanoTime.getAsLong());
		emitter.onCompletion(() -> close(true));
		emitter.onTimeout(() -> close(true));
		emitter.onError(exception -> close(true));
	}

	Long userId() {
		return userId;
	}

	Long sessionId() {
		return sessionId;
	}

	SseEmitter emitter() {
		return emitter;
	}

	boolean isRunning() {
		return running.get() && !closed.get();
	}

	boolean isClosed() {
		return closed.get();
	}

	synchronized boolean begin(AiStreamCancellation streamCancellation) {
		if (closed.get() || !running.compareAndSet(false, true)) {
			return false;
		}
		cancellation = streamCancellation;
		return true;
	}

	synchronized void send(TurnStreamEvent event) {
		switch (event.type()) {
			case STATUS -> sendEvent(
				"status",
				Map.of("stage", event.stage())
			);
			case THOUGHT_SUMMARY -> sendEvent(
				"thought_summary",
				Map.of("text", event.text())
			);
			case CONTENT_DELTA -> sendEvent(
				"content_delta",
				Map.of("text", event.text())
			);
			case HEARTBEAT -> sendHeartbeat();
		}
	}

	synchronized void sendUiAction(UiAction action) {
		sendEvent("ui_action", Map.of("action", action));
	}

	synchronized void sendCompleted(TurnResponse response) {
		if (!closed.compareAndSet(false, true)) {
			throw interrupted(null);
		}
		try {
			sendRaw(SseEmitter.event()
				.name("completed")
				.data(Map.of("result", response), MediaType.APPLICATION_JSON));
			emitter.complete();
		} finally {
			finish(false);
		}
	}

	synchronized void sendError(SessionStreamError error) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			sendRaw(SseEmitter.event()
				.name("error")
				.data(error, MediaType.APPLICATION_JSON));
			emitter.complete();
		} catch (AiClientException ignored) {
			// The client may already be gone, so the original failure wins.
		} finally {
			finish(false);
		}
	}

	synchronized void replaceIdle() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			emitter.complete();
		} finally {
			finish(false);
		}
	}

	synchronized void sendHeartbeatIfIdle(long intervalNanos) {
		if (closed.get()
			|| nanoTime.getAsLong() - lastSentNanos.get()
				< intervalNanos) {
			return;
		}
		try {
			sendHeartbeat();
		} catch (AiClientException ignored) {
			// sendHeartbeat already closes and cleans up the connection.
		}
	}

	void heartbeatTask(ScheduledFuture<?> task) {
		heartbeatTask = task;
	}

	private void sendEvent(String name, Object data) {
		if (closed.get()) {
			throw interrupted(null);
		}
		sendRaw(SseEmitter.event()
			.name(name)
			.data(data, MediaType.APPLICATION_JSON));
	}

	private void sendHeartbeat() {
		if (closed.get()) {
			throw interrupted(null);
		}
		sendRaw(SseEmitter.event().comment("heartbeat"));
	}

	private void sendRaw(SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
			lastSentNanos.set(nanoTime.getAsLong());
		} catch (IOException | IllegalStateException exception) {
			close(true);
			throw interrupted(exception);
		}
	}

	private void close(boolean cancelUpstream) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		finish(cancelUpstream);
	}

	private void finish(boolean cancelUpstream) {
		running.set(false);
		ScheduledFuture<?> currentHeartbeat = heartbeatTask;
		if (currentHeartbeat != null) {
			currentHeartbeat.cancel(false);
		}
		AiStreamCancellation activeCancellation = cancellation;
		cancellation = null;
		if (cancelUpstream && activeCancellation != null) {
			activeCancellation.cancel();
		}
		cleanup.run();
	}

	private AiClientException interrupted(Throwable cause) {
		return new AiClientException(
			ErrorCode.AI_STREAM_INTERRUPTED,
			AiFailureCategory.INTERNAL,
			true,
			cause
		);
	}
}
