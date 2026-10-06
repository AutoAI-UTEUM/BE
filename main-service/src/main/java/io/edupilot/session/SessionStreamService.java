package io.edupilot.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.session.dto.TurnResponse;
import jakarta.annotation.PreDestroy;

@Service
public class SessionStreamService {

	private static final Logger log =
		LoggerFactory.getLogger(SessionStreamService.class);
	static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

	private final LearningSessionRepository sessionRepository;
	private final MaterialAccessService materialAccessService;
	private final SessionStreamAccessGuard streamAccess;
	private final Supplier<SseEmitter> emitterFactory;
	private final Map<Long, SessionStreamConnection> connections =
		new ConcurrentHashMap<>();
	private final ScheduledExecutorService heartbeatScheduler =
		Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform()
				.daemon()
				.name("session-sse-heartbeat")
				.factory()
		);

	@Autowired
	public SessionStreamService(
		LearningSessionRepository sessionRepository,
		MaterialAccessService materialAccessService,
		SessionStreamAccessGuard streamAccess
	) {
		this(sessionRepository, materialAccessService, streamAccess, () -> new SseEmitter(0L));
	}

	SessionStreamService(
		LearningSessionRepository sessionRepository,
		MaterialAccessService materialAccessService,
		SessionStreamAccessGuard streamAccess,
		Supplier<SseEmitter> emitterFactory
	) {
		this.sessionRepository = sessionRepository;
		this.materialAccessService = materialAccessService;
		this.streamAccess = streamAccess;
		this.emitterFactory = emitterFactory;
	}

	public SseEmitter connect(Long userId, Long sessionId) {
		LearningSession session = sessionRepository.findByIdAndUser_Id(
				sessionId,
				userId
			)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.SESSION_NOT_ACTIVE);
		}
		materialAccessService.assertAccessible(userId, session.getMaterialId());
		SessionStreamAccessGuard.Access connectedAccess = streamAccess.captureAccess(userId);

		SessionStreamConnection[] holder = new SessionStreamConnection[1];
		SessionStreamConnection connection = new SessionStreamConnection(
			userId,
			sessionId,
			() -> streamAccess.assertAccessible(userId, sessionId, connectedAccess),
			() -> remove(sessionId, holder[0]),
			emitterFactory.get()
		);
		holder[0] = connection;
		SessionStreamConnection[] previous = new SessionStreamConnection[1];
		SessionStreamConnection.CloseWork[] retirement =
			new SessionStreamConnection.CloseWork[1];
		String[] previousState = new String[1];
		try {
			connections.compute(sessionId, (id, existing) -> {
				previous[0] = existing;
				if (existing != null) {
					previousState[0] = existing.state();
					retirement[0] = existing.reserveIdleReplacement();
				}
				return connection;
			});
		} catch (BusinessException exception) {
			logConnectionTransition(
				connection, previous[0], previousState[0], "REJECTED"
			);
			connection.rejectRegistration();
			throw exception;
		}
		logConnectionTransition(
			connection, previous[0], previousState[0], "REGISTERED"
		);
		// Never send, cancel upstream, or invoke cleanup while holding a Map lock.
		if (previous[0] != null) {
			previous[0].finishClose(retirement[0], true);
		}
		connection.heartbeatTask(heartbeatScheduler.scheduleAtFixedRate(
			() -> connection.sendHeartbeatIfIdle(
				HEARTBEAT_INTERVAL.toNanos()
			),
			HEARTBEAT_INTERVAL.toNanos(),
			HEARTBEAT_INTERVAL.toNanos(),
			TimeUnit.NANOSECONDS
		));
		connection.sendReady(connection.createdAt());
		return connection.emitter();
	}

	public Optional<SessionStreamConnection> beginTurn(
		Long userId,
		Long sessionId,
		String requestId,
		AiStreamCancellation cancellation
	) {
		SessionStreamConnection[] selected = new SessionStreamConnection[1];
		String[] reason = {"NO_CONNECTION"};
		String turnTraceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
		connections.computeIfPresent(sessionId, (id, connection) -> {
			if (!connection.userId().equals(userId)) {
				reason[0] = "OWNER_MISMATCH";
			} else if (connection.isClosed()) {
				reason[0] = "CONNECTION_CLOSED";
			} else if (!connection.isReady()) {
				reason[0] = "READY_NOT_SENT";
			} else if (connection.begin(cancellation, requestId, turnTraceId)) {
				selected[0] = connection;
				reason[0] = "READY_CONNECTION";
			} else {
				reason[0] = connection.isClosed()
					? "CONNECTION_CLOSED" : "TURN_IN_PROGRESS";
			}
			return connection;
		});
		log.atInfo()
			.addKeyValue("sessionId", sessionId)
			.addKeyValue("requestId", requestId)
			.addKeyValue("turnTraceId", turnTraceId)
			.addKeyValue("connectionId",
				selected[0] == null ? null : selected[0].connectionId())
			.addKeyValue("transport", reason[0].equals("TURN_IN_PROGRESS")
				? null : selected[0] == null ? "JSON" : "SSE")
			.addKeyValue("reason", reason[0])
			.addKeyValue("occurredAt", Instant.now())
			.log("Session turn transport selected");
		if (reason[0].equals("TURN_IN_PROGRESS")) {
			throw new BusinessException(ErrorCode.TURN_IN_PROGRESS);
		}
		return Optional.ofNullable(selected[0]);
	}

	public boolean cancelTurn(Long userId, Long sessionId) {
		SessionStreamConnection connection = connections.get(sessionId);
		if (connection == null
			|| connection.isClosed()
			|| !connection.userId().equals(userId)) {
			return false;
		}
		return connection.cancelTurn();
	}

	void assertAccessible(SessionStreamConnection connection) {
		// 철회 후 재승인된 턴을 이전 동의의 SSE 연결로 실행하지 않는다.
		connection.assertAccess();
	}

	public void complete(
		SessionStreamConnection connection,
		String requestId,
		TurnResponse response
	) {
		for (UiAction action : response.uiActions()) {
			connection.sendUiAction(action);
		}
		connection.sendCompleted(requestId, response);
	}

	public void fail(
		SessionStreamConnection connection,
		RuntimeException exception
	) {
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		AiFailureCategory category = AiFailureCategory.INTERNAL;
		boolean retryable = false;
		String message = errorCode.message();
		if (exception instanceof BusinessException businessException) {
			errorCode = businessException.errorCode();
			message = businessException.clientMessage();
		}
		if (exception instanceof AiClientException aiException) {
			category = aiException.category();
			retryable = aiException.retryable();
		} else if (errorCode == ErrorCode.AI_SERVICE_TIMEOUT) {
			category = AiFailureCategory.TIMEOUT;
			retryable = true;
		}
		connection.sendError(new SessionStreamError(
			errorCode.code(),
			category.name(),
			message,
			retryable,
			traceId()
		));
	}

	@PreDestroy
	void shutdown() {
		connections.values().forEach(SessionStreamConnection::shutdown);
		heartbeatScheduler.shutdownNow();
	}

	private void remove(
		Long sessionId,
		SessionStreamConnection connection
	) {
		if (connection != null) {
			connections.remove(sessionId, connection);
		}
	}

	private void logConnectionTransition(
		SessionStreamConnection connection,
		SessionStreamConnection previous,
		String previousState,
		String result
	) {
		log.atInfo()
			.addKeyValue("sessionId", connection.sessionId())
			.addKeyValue("connectionId", connection.connectionId())
			.addKeyValue("previousConnectionId",
				previous == null ? null : previous.connectionId())
			.addKeyValue("previousState", previousState)
			.addKeyValue("result", result)
			.addKeyValue("connectionTraceId", connection.connectionTraceId())
			.addKeyValue("connectionCreatedAt", connection.createdAt())
			.addKeyValue("occurredAt", Instant.now())
			.log("Session SSE connection registration");
	}

	private String traceId() {
		String value = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
		return value == null ? "unknown" : value;
	}
}
