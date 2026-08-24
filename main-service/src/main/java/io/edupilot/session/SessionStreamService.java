package io.edupilot.session;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.session.dto.TurnResponse;
import jakarta.annotation.PreDestroy;

@Service
public class SessionStreamService {

	private static final Logger log =
		LoggerFactory.getLogger(SessionStreamService.class);
	static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

	private final LearningSessionRepository sessionRepository;
	private final Map<Long, SessionStreamConnection> connections =
		new ConcurrentHashMap<>();
	private final ScheduledExecutorService heartbeatScheduler =
		Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform()
				.daemon()
				.name("session-sse-heartbeat")
				.factory()
		);

	public SessionStreamService(
		LearningSessionRepository sessionRepository
	) {
		this.sessionRepository = sessionRepository;
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

		SessionStreamConnection existing = connections.get(sessionId);
		if (existing != null && existing.isRunning()) {
			throw new BusinessException(ErrorCode.TURN_IN_PROGRESS);
		}
		if (existing != null) {
			existing.replaceIdle();
		}

		SessionStreamConnection[] holder = new SessionStreamConnection[1];
		SessionStreamConnection connection = new SessionStreamConnection(
			userId,
			sessionId,
			() -> remove(sessionId, holder[0])
		);
		holder[0] = connection;
		SessionStreamConnection previous = connections.put(
			sessionId,
			connection
		);
		if (previous != null && previous != existing) {
			previous.replaceIdle();
		}
		connection.heartbeatTask(heartbeatScheduler.scheduleAtFixedRate(
			() -> connection.sendHeartbeatIfIdle(
				HEARTBEAT_INTERVAL.toNanos()
			),
			HEARTBEAT_INTERVAL.toNanos(),
			HEARTBEAT_INTERVAL.toNanos(),
			TimeUnit.NANOSECONDS
		));
		log.atInfo()
			.addKeyValue("sessionId", sessionId)
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.log("Session SSE connection opened");
		return connection.emitter();
	}

	public Optional<SessionStreamConnection> beginTurn(
		Long userId,
		Long sessionId,
		AiStreamCancellation cancellation
	) {
		SessionStreamConnection connection = connections.get(sessionId);
		if (connection == null
			|| connection.isClosed()
			|| !connection.userId().equals(userId)) {
			return Optional.empty();
		}
		if (!connection.begin(cancellation)) {
			throw new BusinessException(ErrorCode.TURN_IN_PROGRESS);
		}
		return Optional.of(connection);
	}

	public void complete(
		SessionStreamConnection connection,
		TurnResponse response
	) {
		for (UiAction action : response.uiActions()) {
			connection.sendUiAction(action);
		}
		connection.sendCompleted(response);
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
		connections.values().forEach(SessionStreamConnection::replaceIdle);
		heartbeatScheduler.shutdownNow();
	}

	private void remove(
		Long sessionId,
		SessionStreamConnection connection
	) {
		if (connection != null) {
			connections.remove(sessionId, connection);
		}
		log.atInfo()
			.addKeyValue("sessionId", sessionId)
			.addKeyValue("traceId", traceId())
			.log("Session SSE connection closed");
	}

	private String traceId() {
		String value = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
		return value == null ? "unknown" : value;
	}
}
