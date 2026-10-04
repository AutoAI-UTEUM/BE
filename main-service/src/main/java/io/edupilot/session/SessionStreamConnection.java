package io.edupilot.session;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.session.dto.TurnResponse;

final class SessionStreamConnection {

	private static final Logger log =
		LoggerFactory.getLogger(SessionStreamConnection.class);
	private final String connectionId = UUID.randomUUID().toString();
	private final Instant createdAt = Instant.now();
	private final String connectionTraceId =
		MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
	private final Long userId;
	private final Long sessionId;
	private final SseEmitter emitter;
	private final Runnable accessCheck;
	private final Runnable cleanup;
	private final LongSupplier nanoTime;
	// Map transitions only take this short state lock, never the send monitor.
	private final Object lifecycleLock = new Object();
	private volatile boolean closed;
	private volatile boolean running;
	private volatile boolean ready;
	private volatile CloseReason closeReason;
	private volatile TurnContext turnContext;
	private final AtomicLong lastSentNanos;
	private final long createdAtNanos;
	private AiStreamCancellation cancellation;
	private ScheduledFuture<?> heartbeatTask;

	SessionStreamConnection(Long userId, Long sessionId, Runnable accessCheck, Runnable cleanup) {
		this(
			userId,
			sessionId,
			accessCheck,
			cleanup,
			new SseEmitter(0L),
			System::nanoTime
		);
	}

	SessionStreamConnection(
		Long userId,
		Long sessionId,
		Runnable accessCheck,
		Runnable cleanup,
		SseEmitter emitter
	) {
		this(userId, sessionId, accessCheck, cleanup, emitter, System::nanoTime);
	}

	SessionStreamConnection(
		Long userId,
		Long sessionId,
		Runnable accessCheck,
		Runnable cleanup,
		SseEmitter emitter,
		LongSupplier nanoTime
	) {
		this.userId = userId;
		this.sessionId = sessionId;
		this.accessCheck = accessCheck;
		this.cleanup = cleanup;
		this.emitter = emitter;
		this.nanoTime = nanoTime;
		this.createdAtNanos = nanoTime.getAsLong();
		this.lastSentNanos = new AtomicLong(createdAtNanos);
		emitter.onCompletion(() -> close(
			CloseReason.EMITTER_COMPLETION, true, false, null
		));
		emitter.onTimeout(() -> close(
			CloseReason.EMITTER_TIMEOUT, true, false, null
		));
		emitter.onError(exception -> close(
			CloseReason.EMITTER_ERROR, true, false,
			exception.getClass().getSimpleName()
		));
	}

	String connectionId() {
		return connectionId;
	}

	Instant createdAt() {
		return createdAt;
	}

	String connectionTraceId() {
		return connectionTraceId;
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
		return running && !closed;
	}

	boolean isClosed() {
		return closed;
	}

	boolean isReady() {
		return ready && !closed;
	}

	String state() {
		if (closed) {
			return "CLOSED";
		}
		if (running) {
			return "RUNNING";
		}
		return ready ? "IDLE" : "PREPARING";
	}

	CloseReason closeReason() {
		return closeReason;
	}

	synchronized void sendReady(Instant connectedAt) {
		sendEvent("ready", Map.of(
			"sessionId", sessionId,
			"connectedAt", connectedAt.toString()
		));
		connectionLog().addKeyValue("readyResult", "SENT")
			.log("Session SSE ready delivery");
		synchronized (lifecycleLock) {
			if (closed) {
				throw interrupted(null);
			}
			ready = true;
		}
	}

	boolean begin(AiStreamCancellation streamCancellation) {
		return begin(streamCancellation, null, null);
	}

	boolean begin(
		AiStreamCancellation streamCancellation,
		String requestId,
		String turnTraceId
	) {
		synchronized (lifecycleLock) {
			if (closed || running) {
				return false;
			}
			cancellation = streamCancellation;
			turnContext = new TurnContext(requestId, turnTraceId, null, null);
			running = true;
			return true;
		}
	}

	void aiAttempt(String turnId, int attempt) {
		synchronized (lifecycleLock) {
			if (closed || turnContext == null) {
				return;
			}
			turnContext = new TurnContext(
				turnContext.requestId(), turnContext.traceId(), turnId, attempt
			);
		}
	}

	boolean cancelTurn() {
		AiStreamCancellation activeCancellation;
		synchronized (lifecycleLock) {
			if (closed || !running || cancellation == null) {
				return false;
			}
			activeCancellation = cancellation;
		}
		activeCancellation.cancelByUser();
		connectionLog().addKeyValue("reason", "USER_CANCELLED")
			.log("Session SSE turn cancellation requested");
		return true;
	}

	synchronized void send(TurnStreamEvent event) {
		switch (event.type()) {
			case QUIZ_QUESTION -> sendEvent("quiz_question", event.quizQuestion());
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

	synchronized void sendCompleted(
		String requestId,
		TurnResponse response
	) {
		assertAccess();
		CloseWork work = reserveClose(CloseReason.COMPLETED, false, null);
		if (work == null) {
			throw interrupted(null);
		}
		try {
			sendRaw("completed", SseEmitter.event()
				.name("completed")
				.data(Map.of(
					"requestId", requestId,
					"result", response
				), MediaType.APPLICATION_JSON));
			emitter.complete();
		} finally {
			finishClose(work, false);
		}
	}

	synchronized void sendError(SessionStreamError error) {
		if (closed) return;
		try {
			assertAccess();
		} catch (AiClientException denied) {
			return;
		}
		CloseWork work = reserveClose(CloseReason.APPLICATION_ERROR, false, null);
		if (work == null) {
			return;
		}
		try {
			sendRaw("error", SseEmitter.event()
				.name("error")
				.data(error, MediaType.APPLICATION_JSON));
			emitter.complete();
		} catch (AiClientException ignored) {
			// The client may already be gone, so the original failure wins.
		} finally {
			finishClose(work, false);
		}
	}

	CloseWork reserveIdleReplacement() {
		synchronized (lifecycleLock) {
			if (running && !closed) {
				throw new BusinessException(ErrorCode.TURN_IN_PROGRESS);
			}
			return reserveClose(CloseReason.IDLE_REPLACED, false, null);
		}
	}

	void replaceIdle() {
		finishClose(reserveIdleReplacement(), true);
	}

	void shutdown() {
		close(CloseReason.SERVICE_SHUTDOWN, true, true, null);
	}

	void rejectRegistration() {
		close(CloseReason.REGISTRATION_REJECTED, false, true, null);
	}

	synchronized void sendHeartbeatIfIdle(long intervalNanos) {
		if (closed
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
		synchronized (lifecycleLock) {
			if (!closed) {
				heartbeatTask = task;
				return;
			}
		}
		task.cancel(false);
	}

	private void sendEvent(String name, Object data) {
		assertAccess();
		sendRaw(name, SseEmitter.event()
			.name(name)
			.data(data, MediaType.APPLICATION_JSON));
	}

	private void sendHeartbeat() {
		assertAccess();
		sendRaw("heartbeat", SseEmitter.event().comment("heartbeat"));
	}

	private void assertAccess() {
		if (closed) throw interrupted(null);
		try {
			accessCheck.run();
		} catch (RuntimeException denied) {
			close(CloseReason.ACCESS_REVOKED, true, true, denied.getClass().getSimpleName());
			throw new AiClientException(ErrorCode.AI_STREAM_INTERRUPTED, AiFailureCategory.INTERNAL, false, denied);
		}
		if (closed) throw interrupted(null);
	}

	private void sendRaw(String name, SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
			lastSentNanos.set(nanoTime.getAsLong());
			if (name.equals("completed") || name.equals("error")) {
				connectionLog().addKeyValue("event", name)
					.addKeyValue("deliveryResult", "SENT")
					.log("Session SSE terminal delivery");
			}
		} catch (IOException | IllegalStateException exception) {
			connectionLog().addKeyValue("event", name)
				.addKeyValue("deliveryResult", "FAILED")
				.addKeyValue("readyResult", name.equals("ready") ? "FAILED" : null)
				.addKeyValue("errorType", exception.getClass().getSimpleName())
				.log("Session SSE event delivery failed");
			close(sendFailure(name), true, false,
				exception.getClass().getSimpleName());
			throw interrupted(exception);
		}
	}

	private void close(
		CloseReason reason,
		boolean cancelUpstream,
		boolean completeEmitter,
		String errorType
	) {
		finishClose(reserveClose(reason, cancelUpstream, errorType), completeEmitter);
	}

	private CloseWork reserveClose(
		CloseReason reason,
		boolean cancelUpstream,
		String errorType
	) {
		synchronized (lifecycleLock) {
			if (closed) {
				return null;
			}
			CloseWork work = new CloseWork(
				reason, running, cancellation, heartbeatTask, turnContext,
				cancelUpstream, errorType, nanoTime.getAsLong(), Instant.now()
			);
			closeReason = reason;
			closed = true;
			running = false;
			cancellation = null;
			heartbeatTask = null;
			return work;
		}
	}

	// Called outside Map.compute and lifecycleLock, including emitter callbacks.
	void finishClose(CloseWork work, boolean completeEmitter) {
		if (work == null) {
			return;
		}
		try {
			if (work.heartbeat() != null) {
				work.heartbeat().cancel(false);
			}
			if (work.cancelUpstream() && work.cancellation() != null) {
				work.cancellation().cancel();
			}
			if (completeEmitter) {
				emitter.complete();
			}
		} finally {
			cleanup.run();
			connectionLog(work.turn(), work.closedAt())
				.addKeyValue("reason", work.reason())
				.addKeyValue("wasRunning", work.wasRunning())
				.addKeyValue("upstreamCancelRequested", work.cancelUpstream()
					&& work.cancellation() != null)
				.addKeyValue("upstreamCancelled", work.cancellation() != null
					&& work.cancellation().isCancelled())
				.addKeyValue("userCancelled", work.cancellation() != null
					&& work.cancellation().isUserCancelled())
				.addKeyValue("errorType", work.errorType())
				.addKeyValue("lifetimeMs", TimeUnit.NANOSECONDS.toMillis(
					Math.max(0, work.closedAtNanos() - createdAtNanos)))
				.log("Session SSE connection closed");
		}
	}

	private LoggingEventBuilder connectionLog() {
		return connectionLog(turnContext, Instant.now());
	}

	private LoggingEventBuilder connectionLog(
		TurnContext turn,
		Instant occurredAt
	) {
		return log.atInfo()
			.addKeyValue("connectionId", connectionId)
			.addKeyValue("sessionId", sessionId)
			.addKeyValue("connectionTraceId", connectionTraceId)
			.addKeyValue("connectionCreatedAt", createdAt)
			.addKeyValue("occurredAt", occurredAt)
			.addKeyValue("requestId", turn == null ? null : turn.requestId())
			.addKeyValue("turnTraceId", turn == null ? null : turn.traceId())
			.addKeyValue("turnId", turn == null ? null : turn.turnId())
			.addKeyValue("attempt", turn == null ? null : turn.attempt());
	}

	private CloseReason sendFailure(String name) {
		return switch (name) {
			case "ready" -> CloseReason.READY_SEND_FAILED;
			case "heartbeat" -> CloseReason.HEARTBEAT_SEND_FAILED;
			case "status" -> CloseReason.STATUS_SEND_FAILED;
			case "content_delta" -> CloseReason.CONTENT_SEND_FAILED;
			default -> CloseReason.EVENT_SEND_FAILED;
		};
	}

	enum CloseReason {
		COMPLETED, APPLICATION_ERROR, IDLE_REPLACED, READY_SEND_FAILED,
		STATUS_SEND_FAILED, CONTENT_SEND_FAILED, HEARTBEAT_SEND_FAILED,
		EVENT_SEND_FAILED, EMITTER_TIMEOUT, EMITTER_ERROR, EMITTER_COMPLETION,
		SERVICE_SHUTDOWN, REGISTRATION_REJECTED, ACCESS_REVOKED
	}

	private record TurnContext(
		String requestId,
		String traceId,
		String turnId,
		Integer attempt
	) {
	}

	record CloseWork(
		CloseReason reason,
		boolean wasRunning,
		AiStreamCancellation cancellation,
		ScheduledFuture<?> heartbeat,
		TurnContext turn,
		boolean cancelUpstream,
		String errorType,
		long closedAtNanos,
		Instant closedAt
	) {
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
