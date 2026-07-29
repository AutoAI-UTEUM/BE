package io.edupilot.session;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import tools.jackson.databind.JsonNode;

@Service
public class SessionTurnService {

	private static final Logger log =
		LoggerFactory.getLogger(SessionTurnService.class);
	private static final String SCHEMA_VERSION = "1.0";
	private static final Set<String> QUIZ_TYPES = Set.of(
		"MCQ",
		"OX",
		"SHORT",
		"ESSAY"
	);

	private final TurnClaimService claimService;
	private final TurnPreparationService preparationService;
	private final TurnSnapshotService snapshotService;
	private final AiClient aiClient;
	private final TurnResponseValidator responseValidator;
	private final TurnPersistenceService persistenceService;
	private final LearnerMemoryPromotionService memoryPromotionService;
	private final SessionStreamService streamService;
	private final AiClientProperties aiClientProperties;
	private final LongSupplier nanoTime;

	@Autowired
	public SessionTurnService(
		TurnClaimService claimService,
		TurnPreparationService preparationService,
		TurnSnapshotService snapshotService,
		AiClient aiClient,
		TurnResponseValidator responseValidator,
		TurnPersistenceService persistenceService,
		LearnerMemoryPromotionService memoryPromotionService,
		SessionStreamService streamService,
		AiClientProperties aiClientProperties
	) {
		this(
			claimService,
			preparationService,
			snapshotService,
			aiClient,
			responseValidator,
			persistenceService,
			memoryPromotionService,
			streamService,
			aiClientProperties,
			System::nanoTime
		);
	}

	SessionTurnService(
		TurnClaimService claimService,
		TurnPreparationService preparationService,
		TurnSnapshotService snapshotService,
		AiClient aiClient,
		TurnResponseValidator responseValidator,
		TurnPersistenceService persistenceService,
		LearnerMemoryPromotionService memoryPromotionService,
		SessionStreamService streamService,
		AiClientProperties aiClientProperties,
		LongSupplier nanoTime
	) {
		this.claimService = claimService;
		this.preparationService = preparationService;
		this.snapshotService = snapshotService;
		this.aiClient = aiClient;
		this.responseValidator = responseValidator;
		this.persistenceService = persistenceService;
		this.memoryPromotionService = memoryPromotionService;
		this.streamService = streamService;
		this.aiClientProperties = aiClientProperties;
		this.nanoTime = nanoTime;
	}

	public TurnResponse execute(
		Long userId,
		Long sessionId,
		TurnRequest request
	) {
		TurnEventType eventType = parseEventType(request.eventType());
		ValidatedPayload payload = validatePayload(
			eventType,
			request.payload()
		);
		claimService.claim(userId, sessionId, request.requestId());
		SessionStreamConnection streamConnection = null;
		try {
			PreparedTurn prepared;
			try {
				prepared = preparationService.prepare(
					userId,
					sessionId,
					request.requestId(),
					payload.userContent(),
					payload.diagnosisId()
				);
			} catch (DataIntegrityViolationException exception) {
				throw new BusinessException(
					ErrorCode.TURN_ALREADY_PROCESSED
				);
			}
			TurnSnapshot snapshot = snapshotService.build(
				userId,
				sessionId,
				prepared.userMessageId()
			);
			AiStreamCancellation cancellation = new AiStreamCancellation();
			Optional<SessionStreamConnection> activeStream =
				streamService.beginTurn(
					userId,
					sessionId,
					cancellation
				);
			streamConnection = activeStream.orElse(null);
			io.edupilot.ai.dto.TurnResponse aiResponse =
				streamConnection == null
					? executeAiTurn(request, eventType, snapshot)
					: executeAiTurnStream(
						request,
						eventType,
						snapshot,
						streamConnection,
						cancellation
					);
			if (streamConnection != null && cancellation.isCancelled()) {
				throw new AiClientException(
					ErrorCode.AI_STREAM_INTERRUPTED,
					true,
					null
				);
			}
			PersistedTurn persisted = persistenceService.persist(
				userId,
				sessionId,
				request.requestId(),
				eventType,
				payload.diagnosisId(),
				prepared.userMessageId(),
				aiResponse
			);
			promoteMemory(userId, persisted);
			TurnResponse response = persisted.response();
			if (streamConnection != null) {
				streamService.complete(streamConnection, response);
			}
			return response;
		} catch (RuntimeException exception) {
			if (streamConnection != null) {
				streamService.fail(streamConnection, exception);
			}
			throw exception;
		} finally {
			claimService.release(sessionId, request.requestId());
		}
	}

	private io.edupilot.ai.dto.TurnResponse executeAiTurnStream(
		TurnRequest request,
		TurnEventType eventType,
		TurnSnapshot snapshot,
		SessionStreamConnection streamConnection,
		AiStreamCancellation cancellation
	) {
		long deadlineNanos = nanoTime.getAsLong()
			+ aiClientProperties.turnReadTimeout().toNanos();
		AtomicBoolean contentForwarded = new AtomicBoolean();
		for (int attempt = 1; attempt <= 2; attempt++) {
			String turnId = "turn-" + UUID.randomUUID();
			io.edupilot.ai.dto.TurnRequest aiRequest = aiRequest(
				turnId,
				eventType,
				request,
				snapshot
			);
			try {
				long remainingNanos = deadlineNanos - nanoTime.getAsLong();
				if (remainingNanos <= 0) {
					throw new AiClientException(
						ErrorCode.AI_SERVICE_TIMEOUT,
						true,
						null
					);
				}
				io.edupilot.ai.dto.TurnResponse response =
					aiClient.executeTurnStream(
						aiRequest,
						event -> {
							if (event.type()
								== TurnStreamEvent.Type.CONTENT_DELTA) {
								contentForwarded.set(true);
							}
							streamConnection.send(event);
						},
						cancellation,
						Duration.ofNanos(remainingNanos)
					);
				responseValidator.validate(
					response,
					turnId,
					qaThreadRef(snapshot),
					eventType,
					expectedQuizType(eventType, request.payload()),
					availableQuizPages(eventType, snapshot)
				);
				return response;
			} catch (AiClientException exception) {
				logAttemptFailure(
					request.requestId(),
					turnId,
					attempt,
					exception
				);
				if (attempt == 1
					&& exception.retryable()
					&& !contentForwarded.get()
					&& !cancellation.isCancelled()
					&& deadlineNanos > nanoTime.getAsLong()) {
					continue;
				}
				throw exception;
			}
		}
		throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	private io.edupilot.ai.dto.TurnResponse executeAiTurn(
		TurnRequest request,
		TurnEventType eventType,
		TurnSnapshot snapshot
	) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			String turnId = "turn-" + UUID.randomUUID();
			io.edupilot.ai.dto.TurnRequest aiRequest = aiRequest(
				turnId,
				eventType,
				request,
				snapshot
			);
			try {
				io.edupilot.ai.dto.TurnResponse response =
					aiClient.executeTurn(aiRequest);
				responseValidator.validate(
					response,
					turnId,
					qaThreadRef(snapshot),
					eventType,
					expectedQuizType(eventType, request.payload()),
					availableQuizPages(eventType, snapshot)
				);
				return response;
			} catch (AiClientException exception) {
				logAttemptFailure(
					request.requestId(),
					turnId,
					attempt,
					exception
				);
				if (attempt == 1 && exception.retryable()) {
					continue;
				}
				throw exception;
			}
		}
		throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	private io.edupilot.ai.dto.TurnRequest aiRequest(
		String turnId,
		TurnEventType eventType,
		TurnRequest request,
		TurnSnapshot snapshot
	) {
		return new io.edupilot.ai.dto.TurnRequest(
			SCHEMA_VERSION,
			turnId,
			snapshot.session(),
			eventData(eventType, request.payload()),
			snapshot.context()
		);
	}

	private void logAttemptFailure(
		String requestId,
		String turnId,
		int attempt,
		AiClientException exception
	) {
		log.atWarn()
			.addKeyValue("requestId", requestId)
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("attempt", attempt)
			.addKeyValue("retried", attempt > 1)
			.addKeyValue("turnId", turnId)
			.addKeyValue("category", exception.category())
			.addKeyValue("errorCode", exception.errorCode().code())
			.addKeyValue("retryable", exception.retryable())
			.log("AI turn attempt failed");
	}

	private Map<String, Object> eventData(
		TurnEventType eventType,
		JsonNode payload
	) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventType", eventType.name());
		event.put("payload", payload);
		return event;
	}

	private String qaThreadRef(TurnSnapshot snapshot) {
		Object rawDigest = snapshot.context().get("qaThreadDigest");
		if (!(rawDigest instanceof Map<?, ?> digest)) {
			return null;
		}
		Object threadRef = digest.get("threadRef");
		return threadRef instanceof String value ? value : null;
	}

	private String expectedQuizType(
		TurnEventType eventType,
		JsonNode payload
	) {
		return eventType == TurnEventType.QUIZ_TYPE_SELECTED
			? payload.get("quizType").stringValue()
			: null;
	}

	private Set<Integer> availableQuizPages(
		TurnEventType eventType,
		TurnSnapshot snapshot
	) {
		if (eventType != TurnEventType.QUIZ_TYPE_SELECTED) {
			return Set.of();
		}
		Object rawCurrentPage = snapshot.session().get("currentPage");
		if (!(rawCurrentPage instanceof Number number)) {
			throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
		}
		int currentPage = number.intValue();
		Set<Integer> pages = new java.util.LinkedHashSet<>();
		if (snapshot.context().get("previousPageText") instanceof String) {
			pages.add(currentPage - 1);
		}
		if (snapshot.context().get("currentPageText") instanceof String) {
			pages.add(currentPage);
		}
		if (snapshot.context().get("nextPageText") instanceof String) {
			pages.add(currentPage + 1);
		}
		return Set.copyOf(pages);
	}

	private void promoteMemory(Long userId, PersistedTurn persisted) {
		if (persisted.memoryWrite() == null) {
			return;
		}
		try {
			memoryPromotionService.promoteMemory(
				userId,
				persisted.materialId(),
				persisted.memoryWrite()
			);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("userId", userId)
				.addKeyValue("materialId", persisted.materialId())
				.addKeyValue(
					"errorType",
					exception.getClass().getSimpleName()
				)
				.log("Learner memory promotion failed after turn commit");
		}
	}

	private TurnEventType parseEventType(String value) {
		try {
			return TurnEventType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_EVENT_TYPE);
		}
	}

	private ValidatedPayload validatePayload(
		TurnEventType eventType,
		JsonNode payload
	) {
		if (!payload.isObject()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return switch (eventType) {
			case EXPLAIN_CURRENT_PAGE -> {
				String detailLevel = requiredText(payload, "detailLevel");
				if (!Set.of("NORMAL", "DETAILED").contains(detailLevel)) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					"현재 페이지 설명 요청: " + detailLevel,
					null
				);
			}
			case USER_QUESTION -> new ValidatedPayload(
				requiredText(payload, "message"),
				null
			);
			case QUIZ_TYPE_SELECTED -> {
				String quizType = requiredText(payload, "quizType");
				if (!QUIZ_TYPES.contains(quizType)) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					"퀴즈 유형 선택: " + quizType,
					null
				);
			}
			case DIAGNOSIS_ANSWER_SUBMITTED -> {
				JsonNode diagnosisId = payload.get("diagnosisId");
				if (diagnosisId == null
					|| !diagnosisId.canConvertToLong()
					|| diagnosisId.longValue() < 1) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					requiredText(payload, "answer"),
					diagnosisId.longValue()
				);
			}
		};
	}

	private String requiredText(JsonNode payload, String field) {
		JsonNode value = payload.get(field);
		if (value == null
			|| !value.isTextual()
			|| value.textValue().isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return value.textValue().trim();
	}

	private record ValidatedPayload(
		String userContent,
		Long diagnosisId
	) {
	}
}
