package io.edupilot.composer;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.global.error.BusinessException;
import io.edupilot.session.SessionStreamAccessGuard;
import jakarta.annotation.PreDestroy;

/** Separate optional SSE; never joins the turn transaction, claim, or completed event. */
@Service
@ConditionalOnProperty(name = "edupilot.composer.enabled", havingValue = "true")
public class ComposerPredictionService {
	private static final Logger log = LoggerFactory.getLogger(ComposerPredictionService.class);
	private static final List<ComposerAiResponse.Warning> UNAVAILABLE = List.of(new ComposerAiResponse.Warning(
		"PREDICTION_UNAVAILABLE", "Prediction is temporarily unavailable. You can type your own question."));
	private final ComposerProperties policy;
	private final ComposerSnapshotService snapshots;
	private final SessionStreamAccessGuard access;
	private final ComposerAdmissionService admissions;
	private final ComposerAiClient ai;
	// Technical resource cap only. No unbounded request queue, independent of user policy.
	private final Semaphore capacity = new Semaphore(32);
	private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
	private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2,
		Thread.ofPlatform().daemon().name("composer-watch-", 0).factory());

	public ComposerPredictionService(ComposerProperties policy, ComposerSnapshotService snapshots,
		SessionStreamAccessGuard access, ComposerAdmissionService admissions, ComposerAiClient ai) {
		this.policy = policy;
		this.snapshots = snapshots;
		this.access = access;
		this.admissions = admissions;
		this.ai = ai;
	}

	public SseEmitter open(Long userId, Long sessionId, Long sourceMessageId) {
		var grant = access.captureAccess(userId);
		var snapshot = snapshots.capture(userId, sessionId, sourceMessageId, grant);
		SseEmitter emitter = newEmitter();
		if (!policy.ready() || snapshot == null) {
			completeImmediate(emitter, result(sourceMessageId,
				snapshot == null ? null : snapshot.request().contextVersion(), null, List.of()));
			return emitter;
		}
		if (!capacity.tryAcquire()) {
			completeImmediate(emitter, result(sourceMessageId, snapshot.request().contextVersion(), null, UNAVAILABLE));
			return emitter;
		}
		Connection connection = new Connection(snapshot, grant, emitter);
		connections.add(connection);
		try {
			if (!admissions.reserve(userId, grant.role(), snapshot.reservationId())) {
				connection.complete(null, "LIMITED_OR_DUPLICATE");
				return emitter;
			}
			connection.schedule();
			executor.execute(connection::start);
		} catch (BusinessException denied) {
			connection.cancel();
			throw denied;
		} catch (RuntimeException unavailable) {
			connection.complete(null, "UNAVAILABLE");
		}
		return emitter;
	}

	SseEmitter newEmitter() { return new SseEmitter(0L); }

	private static void completeImmediate(SseEmitter emitter, Result result) {
		try {
			emitter.send(SseEmitter.event().name("prediction").data(result, MediaType.APPLICATION_JSON));
			emitter.complete();
		} catch (IOException | IllegalStateException ignored) {
			emitter.complete();
		}
	}

	@PreDestroy
	public void close() {
		connections.forEach(Connection::cancel);
		timer.shutdownNow();
		executor.shutdownNow();
	}

	// Shared names follow AI #570 v1.0. The browser selector and nullable pre-call
	// context distinguish this SSE projection from the strict internal AI response.
	// Usage and provider warning text remain server-only.
	public record Result(String schemaVersion, String sourceTurnId, String contextVersion,
		String suggestion, List<ComposerAiResponse.Warning> warnings, Long sourceMessageId) {
		public Result { warnings = List.copyOf(warnings); }
	}

	private static Result result(Long sourceId, String version, String suggestion, List<ComposerAiResponse.Warning> warnings) {
		return new Result("1.0", "message-" + sourceId, version, suggestion, warnings, sourceId);
	}

	private final class Connection {
		private final ComposerSnapshotService.Snapshot snapshot;
		private final SessionStreamAccessGuard.Access grant;
		private final SseEmitter emitter;
		private boolean closed;
		private ComposerAiClient.Call call;
		private ScheduledFuture<?> watch;
		private ScheduledFuture<?> deadline;
		private final long expiresAtNanos = System.nanoTime() + policy.timeout().toNanos();
		private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();

		Connection(ComposerSnapshotService.Snapshot snapshot, SessionStreamAccessGuard.Access grant, SseEmitter emitter) {
			this.snapshot = snapshot;
			this.grant = grant;
			this.emitter = emitter;
			emitter.onCompletion(this::cancel);
			emitter.onTimeout(this::cancel);
			emitter.onError(ignored -> cancel());
		}

		synchronized void schedule() {
			long remaining = expiresAtNanos - System.nanoTime();
			if (remaining <= 0) { complete(null, "UNAVAILABLE"); return; }
			deadline = timer.schedule(() -> complete(null, "UNAVAILABLE"), remaining, TimeUnit.NANOSECONDS);
			watch = timer.scheduleAtFixedRate(() -> {
				if (checking.compareAndSet(false, true)) executor.execute(() -> {
					try { heartbeat(); } finally { checking.set(false); }
				});
			}, 0, 500, TimeUnit.MILLISECONDS);
		}

		void start() {
			try {
				if (!snapshots.fresh(snapshot, grant)) { complete(null, "STALE"); return; }
				synchronized (this) {
					if (closed) return;
					if (System.nanoTime() >= expiresAtNanos) { complete(null, "UNAVAILABLE"); return; }
					call = ai.start(snapshot.request());
					call.result().whenCompleteAsync(this::received, executor);
				}
			} catch (RuntimeException failure) {
				complete(null, "UNAVAILABLE");
			}
		}

		void received(ComposerAiResponse response, Throwable failure) {
			boolean valid = failure == null && response != null && response.validFor(snapshot.request());
			try {
				// Retain known spend on null, invalid echo, stale result, or failed delivery.
				admissions.finish(snapshot.userId(), snapshot.reservationId(), response == null ? null : response.usage(),
					valid && response.warnings().isEmpty());
			} catch (RuntimeException persistenceFailure) {
				// The committed unknown-cost reservation remains conservative; never refund it.
				log.warn("Composer usage finalization failed; reservation retained");
				complete(null, "UNAVAILABLE");
				return;
			}
			try {
				if (!snapshots.fresh(snapshot, grant)) { complete(null, "STALE"); return; }
				boolean available = valid && response.warnings().isEmpty();
				complete(available ? response.suggestion() : null, available ? "COMPLETED" : "UNAVAILABLE");
			} catch (RuntimeException revoked) {
				complete(null, "STALE");
			}
		}

		void heartbeat() {
			try {
				if (!snapshots.fresh(snapshot, grant)) { complete(null, "STALE"); return; }
				synchronized (this) {
					if (!closed) emitter.send(SseEmitter.event().comment("heartbeat"));
				}
			} catch (IOException | RuntimeException disconnected) { cancel(); }
		}

		synchronized void complete(String suggestion, String reason) {
			if (closed) return;
			try {
				// A final committed access/version check immediately before delivery.
				if (suggestion != null && !snapshots.fresh(snapshot, grant)) {
					suggestion = null;
					reason = "STALE";
				}
				emitter.send(SseEmitter.event().name("prediction").data(result(snapshot.sourceMessageId(),
					snapshot.request().contextVersion(), suggestion, "COMPLETED".equals(reason) ? List.of() : UNAVAILABLE),
					MediaType.APPLICATION_JSON));
			} catch (IOException | RuntimeException ignored) {
				// Optional prediction failures cannot roll back a completed learning turn.
			} finally {
				cancel();
			}
		}

		synchronized void cancel() {
			if (closed) return;
			closed = true;
			if (watch != null) watch.cancel(false);
			if (deadline != null) deadline.cancel(false);
			if (call != null && !call.result().isDone()) call.cancel();
			connections.remove(this);
			capacity.release();
			emitter.complete();
		}
	}
}
